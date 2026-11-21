import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;

final class SearchIndex {
    static final String DATABASE = ".office-index-v1.sqlite";
    private static final int INDEX_BATCH = 64;
    private static final int WRITER_WAIT_MS = 60_000;

    static final long LARGE_BYTES = 100_000_000L;
    record Progress(long checked, long updated, long removed, long deferred) {}
    record Completion(int indexed, int deferred) {}
    record HeldNote(Path path, long size) {
        @Override public String toString() {
            return "%s  (%.1f MB)".formatted(path.getFileName(), size / 1_000_000.0);
        }
    }
    record LargeSettings(boolean hold, List<HeldNote> pending) {}

    enum Mode {
        RELEVANCE("관련도순"), PHRASE("정확한 문구"), AND("모두 포함 (AND)"), OR("하나 이상 (OR)");
        private final String label;
        Mode(String label) { this.label = label; }
        public String toString() { return label; }
        List<String> words(String query) {
            String folded = query.strip().toLowerCase(Locale.ROOT);
            if (this == RELEVANCE) return RelevanceSearch.words(folded);
            if (this == PHRASE || folded.isEmpty()) return List.of(folded);
            return Arrays.stream(folded.split("(?U)\\s+")).filter(s -> !s.isEmpty()).distinct().toList();
        }
    }
    enum Scope {
        ALL("전체"), TITLE("제목"), CONTENT("내용"), IMAGE("그림 OCR");
        private final String label;
        Scope(String label) { this.label = label; }
        public String toString() { return label; }
    }

    private static Path dataDirectory() {
        String override = System.getProperty("officemd.dataDir");
        if (override != null) return Path.of(override).toAbsolutePath().normalize();
        return Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), "OfficeMarkdown");
    }

    static Path defaultLibrary() {
        return dataDirectory().resolve("Markdown");
    }

    static Path database() {
        return dataDirectory().resolve("Index").resolve(DATABASE);
    }

    static List<Library.Hit> search(Path root, String query, Scope scope, int limit) throws IOException {
        return search(root, query, scope, Mode.PHRASE, limit);
    }

    static List<Library.Hit> search(Path root, String query, Scope scope, Mode mode, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("검색 한도는 1 이상이어야 합니다.");
        synchronizeAll(root, ignored -> {});
        return searchCached(root, query, scope, mode, limit);
    }

    static List<Library.Hit> searchCached(Path root, String query, Scope scope, Mode mode, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("검색 한도는 1 이상이어야 합니다.");
        return readIndex(db -> {
            if(mode==Mode.RELEVANCE && !query.isBlank()){
                return scope==Scope.IMAGE ? RelevanceSearch.pictures(db,query,limit,null)
                        : RelevanceSearch.text(db,query,scope,limit);
            }
            var words = query.isBlank()?List.of(""):mode.words(query);
            if (scope == Scope.IMAGE) return findPictures(db, words, mode, limit);
            if (words.size() <= 1) return find(db, words.isEmpty() ? "" : words.getFirst(), scope, limit);
            return findWords(db, words, scope, mode, limit);
        });
    }

    static List<Library.Hit> libraryCards(Path root, int limit) throws IOException {
        return readIndex(db -> {
            String sql = """
                    SELECT d.md, d.source, d.title, coalesce(l.section,'문서'), coalesce(substr(l.text,1,180),'')
                    FROM (SELECT id,md,source,title FROM visible_docs ORDER BY title,md LIMIT ?) d
                    LEFT JOIN lines l ON l.id = (SELECT first.id FROM lines first
                            WHERE first.doc=d.id AND first.kind=2 AND first.text NOT LIKE '## %'
                            ORDER BY first.id LIMIT 1)
                    ORDER BY d.title,d.md
                    """;
            try (var stmt = db.prepareStatement(sql)) {
                stmt.setInt(1,limit);
                return results(stmt);
            }
        });
    }

    static List<Library.Hit> suspectedOcr(Path root, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("검색 한도는 1 이상이어야 합니다.");
        synchronize(root, ignored -> {});
        return suspectedOcrCached(root, limit);
    }

    static List<Library.Hit> suspectedOcrCached(Path root, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("검색 한도는 1 이상이어야 합니다.");
        return readIndex(db -> {
            String query = """
                SELECT d.md,d.source,d.title,p.section,p.text,p.path,p.location
                FROM pictures p JOIN visible_docs d ON d.id=p.doc
                WHERE p.text<>'' OR p.location LIKE '% · 스캔'
                ORDER BY p.id
                """;
            List<Library.Hit> hits = new ArrayList<>();
            try (var stmt = db.createStatement(); var rs = stmt.executeQuery(query)) {
                while (hits.size() < limit && rs.next()) {
                    if (OcrRepair.lowQuality(rs.getString(5),rs.getString(7))) hits.add(pictureHit(rs));
                }
            }
            return hits;
        });
    }

    @FunctionalInterface
    interface Query<T> {
        List<T> run(Connection db) throws SQLException;
    }

    static <T> List<T> readIndex(Query<T> query) throws IOException {
        Path database = database();
        if (!Files.isRegularFile(database)) return List.of();
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(1_500);
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
             Statement sql = db.createStatement()) {
            // Opening can race with creation of a brand-new index. Its schema is published in one transaction.
            try (var tables = sql.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='docs'")) {
                if (!tables.next()) return List.of();
            }
            // Cached searches also run before an older library has been migrated.
            boolean hasQueue;
            try (var tables = sql.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='large_pending'")) {
                hasQueue = tables.next();
            }
            boolean hasRoles;
            try(var table=sql.executeQuery("SELECT 1 FROM sqlite_master WHERE name='document_roles'")){
                hasRoles=table.next();
            }
            String role=hasRoles?"coalesce(r.manual,r.inferred,'')":"''";
            String join=hasRoles?" LEFT JOIN document_roles r ON r.md=d.md":"";
            String predicate = hasQueue ? " WHERE NOT EXISTS (SELECT 1 FROM large_pending p WHERE p.md=d.md)" : "";
            sql.execute("CREATE TEMP VIEW visible_docs AS SELECT d.*,"+role+" AS role FROM main.docs d"+join + predicate);
            boolean hasPictureText;
            try(var t=sql.executeQuery("SELECT 1 FROM sqlite_master WHERE name='picture_search_ocr'")){
                hasPictureText=t.next();
            }
            boolean hasFingerprint;
            try(var t=sql.executeQuery("SELECT 1 FROM sqlite_master WHERE name='picture_fingerprints'")){
                hasFingerprint=t.next();
            }
            sql.execute("CREATE TEMP VIEW searchable_pictures AS SELECT p.*,"+
                    (hasPictureText?"o.text":"NULL")+" AS search_ocr,"+
                    (hasFingerprint?"f.digest":"NULL")+" AS digest FROM pictures p"+
                    (hasPictureText?" LEFT JOIN picture_search_ocr o ON o.id=p.id":"")+
                    (hasFingerprint?" LEFT JOIN picture_fingerprints f ON f.id=p.id":""));
            return query.run(db);
        } catch (SQLException e) {
            throw new IOException("저장된 검색 색인을 열지 못했습니다. " + e.getMessage(), e);
        }
    }

    static void synchronize(Path root, Consumer<Progress> progress) throws IOException {
        Path library = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(library)) return;
        checkInterrupted();
        try (var writer = new IndexWriter(library)) {
            long checked = 0, updated = 0, removed = 0, deferred = 0;
            progress.accept(new Progress(checked, updated, removed, deferred));
            var paths = new ArrayList<Path>(INDEX_BATCH);
            try (var files = Files.newDirectoryStream(library, SearchIndex::markdown)) {
                for (Path file : files) {
                    checkInterrupted();
                    paths.add(file);
                    if (paths.size() == INDEX_BATCH) {
                        var result = writer.write(paths, false);
                        checked += paths.size();
                        updated += result.updated();
                        removed += result.removed();
                        deferred += result.deferred();
                        progress.accept(new Progress(checked, updated, removed, deferred));
                        paths.clear();
                    }
                }
            }
            if (!paths.isEmpty()) {
                var result = writer.write(paths, false);
                checked += paths.size();
                updated += result.updated();
                removed += result.removed();
                deferred += result.deferred();
                progress.accept(new Progress(checked, updated, removed, deferred));
            }

            // Read indexed keys in bounded pages. Recheck absence underneath the writer transaction.
            String after = "";
            for (;;) {
                checkInterrupted();
                var indexed = writer.pathsAfter(after);
                if (indexed.isEmpty()) break;
                after = indexed.getLast();
                var missing = new ArrayList<Path>(INDEX_BATCH);
                for (String path : indexed) {
                    var note = Path.of(path);
                    if (library.equals(note.getParent()) && (Files.notExists(note) || Files.isDirectory(note))) missing.add(note);
                }
                if (!missing.isEmpty()) {
                    removed += writer.write(missing, true).removed();
                    progress.accept(new Progress(checked, updated, removed, deferred));
                }
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        } catch (SQLException e) {
            throw new IOException("검색 색인을 갱신하지 못했습니다. " + e.getMessage(), e);
        }
    }

    static void synchronizeAll(Path output, Consumer<Progress> progress) throws IOException {
        var folders = new LinkedHashSet<Path>();
        folders.add(output.toAbsolutePath().normalize());
        if (Files.isDirectory(defaultLibrary()))
            folders.add(defaultLibrary());
        folders.addAll(readIndex(db -> {
            var known = new ArrayList<Path>();
            try (var sql = db.createStatement(); var rows = sql.executeQuery("SELECT path FROM folders ORDER BY path")) {
                while (rows.next()) known.add(Path.of(rows.getString(1)));
            }
            return known;
        }));
        Progress total = new Progress(0,0,0,0);
        for (Path folder : folders) {
            Progress previous = total;
            Progress[] latest = {previous};
            synchronize(folder, p -> {
                latest[0] = new Progress(previous.checked()+p.checked(), previous.updated()+p.updated(),
                        previous.removed()+p.removed(), previous.deferred()+p.deferred());
                progress.accept(latest[0]);
            });
            total = latest[0];
        }
    }

    static void importPreviousIndex(Path output) throws IOException {
        Path folder = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(folder.resolve(DATABASE))) return;
        var imported = readIndex(db -> {
            try (var query = db.prepareStatement("SELECT imported FROM folders WHERE path=?")) {
                query.setString(1, folder.toString());
                try (var rows = query.executeQuery()) { return rows.next() ? List.of(rows.getInt(1)) : List.<Integer>of(); }
            }
        });
        if (imported.contains(1)) return;
        try (var writer = new IndexWriter(folder)) { /* Constructor commits the migration. */ }
        catch (SQLException e) { throw new IOException("기존 검색 색인을 가져오지 못했습니다.", e); }
    }

    static int indexCompleted(Path root, List<Path> markdown) throws IOException {
        return indexCompletedDetailed(root, markdown).indexed();
    }

    static Completion indexCompletedDetailed(Path root, List<Path> markdown) throws IOException {
        if (markdown.isEmpty()) return new Completion(0, 0);
        Path library = root.toAbsolutePath().normalize();
        checkInterrupted();
        try (var writer = new IndexWriter(library)) {
            int indexed = 0, deferred = 0;
            for (int start = 0; start < markdown.size(); start += INDEX_BATCH) {
                var paths = new ArrayList<Path>(INDEX_BATCH);
                for (Path path : markdown.subList(start, Math.min(start + INDEX_BATCH, markdown.size()))) {
                    Path note = path.toAbsolutePath().normalize();
                    if (note.getParent() == null || !markdown(note)
                            || (!library.equals(note.getParent()) && !Files.isSameFile(library, note.getParent())))
                        throw new IOException("검색 색인 대상이 Markdown 폴더 안의 문서가 아닙니다.");
                    paths.add(library.resolve(note.getFileName()));
                }
                var result = writer.write(paths, false);
                indexed += result.indexed();
                deferred += result.deferred();
            }
            return new Completion(indexed, deferred);
        } catch (SQLException e) {
            throw new IOException("완료한 문서의 색인을 갱신하지 못했습니다. " + e.getMessage(), e);
        }
    }

    static LargeSettings largeSettings(Path root) throws IOException {
        Path library = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(library)) return new LargeSettings(true, List.of());
        try (var writer = new IndexWriter(library)) {
            var notes = new ArrayList<HeldNote>();
            try (var rows = writer.sql.executeQuery("SELECT md,size FROM large_pending ORDER BY md")) {
                while (rows.next()) {
                    Path path = Path.of(rows.getString(1));
                    if (library.equals(path.getParent()) && Files.isRegularFile(path))
                        notes.add(new HeldNote(path, rows.getLong(2)));
                }
            }
            return new LargeSettings(writer.holdLarge(), List.copyOf(notes));
        } catch (SQLException e) { throw new IOException("대용량 색인 설정 조회 실패", e); }
    }

    static void configureLarge(Path root, boolean hold, List<Path> allow) throws IOException {
        Path library = Files.createDirectories(root.toAbsolutePath().normalize());
        try (var writer = new IndexWriter(library);
             var setting = writer.db.prepareStatement("UPDATE folders SET hold_large=? WHERE path=?");
             var permit = writer.db.prepareStatement("INSERT OR IGNORE INTO large_allowed VALUES(?)")) {
            writer.sql.execute("BEGIN IMMEDIATE");
            setting.setInt(1, hold ? 1 : 0);
            setting.setString(2, library.toString());
            setting.executeUpdate();
            for (Path file : allow) {
                Path path = file.toAbsolutePath().normalize();
                if (!library.equals(path.getParent()) || !markdown(path)) throw new IOException("색인 대상 경로 오류");
                permit.setString(1, path.toString());
                permit.executeUpdate();
            }
            writer.sql.execute("COMMIT");
        } catch (SQLException e) { throw new IOException("대용량 색인 설정 저장 실패", e); }
    }

    private static boolean markdown(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("색인 갱신을 중단했습니다.");
    }

    private static void schema(Statement sql) throws SQLException {
        sql.execute("CREATE TABLE IF NOT EXISTS folders(path TEXT PRIMARY KEY,hold_large INTEGER DEFAULT 1, imported INTEGER DEFAULT 0)");
        sql.execute("CREATE TABLE IF NOT EXISTS large_allowed(md TEXT PRIMARY KEY)");
        sql.execute("CREATE TABLE IF NOT EXISTS large_pending(md TEXT PRIMARY KEY,size INTEGER NOT NULL)");
        sql.execute("CREATE TABLE IF NOT EXISTS docs(id INTEGER PRIMARY KEY, md TEXT UNIQUE, stamp TEXT, source TEXT, title TEXT)");
        sql.execute("CREATE TABLE IF NOT EXISTS document_roles(md TEXT PRIMARY KEY,inferred TEXT NOT NULL,manual TEXT)");
        sql.execute("CREATE INDEX IF NOT EXISTS doc_title ON docs(title,md)");
        sql.execute("CREATE TABLE IF NOT EXISTS lines(id INTEGER PRIMARY KEY, doc INTEGER REFERENCES docs ON DELETE CASCADE, kind INTEGER, section TEXT, text TEXT, folded TEXT)");
        sql.execute("CREATE INDEX IF NOT EXISTS doc_lines ON lines(doc)");
        sql.execute("CREATE VIRTUAL TABLE IF NOT EXISTS terms USING fts5(folded,content='lines',content_rowid='id',tokenize='trigram')");
        sql.execute("CREATE TRIGGER IF NOT EXISTS terms_add AFTER INSERT ON lines BEGIN INSERT INTO terms(rowid,folded) VALUES(new.id,new.folded); END");
        sql.execute("CREATE TRIGGER IF NOT EXISTS terms_del AFTER DELETE ON lines BEGIN INSERT INTO terms(terms,rowid,folded) VALUES('delete',old.id,old.folded); END");
        sql.execute("CREATE TABLE IF NOT EXISTS pictures(id INTEGER PRIMARY KEY, doc INTEGER REFERENCES docs ON DELETE CASCADE,"
                + " path TEXT, location TEXT, section TEXT, text TEXT, folded TEXT)");
        sql.execute("CREATE INDEX IF NOT EXISTS doc_pictures ON pictures(doc)");
        sql.execute("CREATE VIRTUAL TABLE IF NOT EXISTS picture_terms USING fts5(folded,content='pictures',content_rowid='id',tokenize='trigram')");
        sql.execute("CREATE TRIGGER IF NOT EXISTS picture_terms_add AFTER INSERT ON pictures BEGIN INSERT INTO picture_terms(rowid,folded) VALUES(new.id,new.folded); END");
        sql.execute("CREATE TRIGGER IF NOT EXISTS picture_terms_del AFTER DELETE ON pictures BEGIN INSERT INTO picture_terms(picture_terms,rowid,folded) VALUES('delete',old.id,old.folded); END");
    }

    private enum Outcome { UNCHANGED, UPDATED, REMOVED, SKIPPED, DEFERRED }

    private record BatchResult(int indexed, int updated, int removed, int deferred) {}

    private static final class IndexWriter implements AutoCloseable {
        private final Connection db;
        private final Statement sql;
        private final Path library;
        private final PreparedStatement lookup;
        private final PreparedStatement clearPending;
        private final PreparedStatement remove;
        private final PreparedStatement doc;
        private final PreparedStatement line;
        private final PreparedStatement picture;

        IndexWriter(Path library) throws SQLException, IOException {
            this.library = library;
            // indexCompleted receives files already published by a conversion.
            if (!Files.isDirectory(library)) throw new NoSuchFileException(library.toString());
            Path database = database();
            Files.createDirectories(database.getParent());
            db = DriverManager.getConnection("jdbc:sqlite:" + database);
            try {
                sql = db.createStatement();
                sql.execute("PRAGMA busy_timeout=" + WRITER_WAIT_MS);
                sql.execute("PRAGMA foreign_keys=ON");
                enableWal(sql);
                sql.execute("BEGIN IMMEDIATE");
                schema(sql);
                try (var register = db.prepareStatement("INSERT OR IGNORE INTO folders(path) VALUES(?)")) {
                    register.setString(1, library.toString());
                    register.executeUpdate();
                }
                importLegacy();
                sql.execute("COMMIT");
                clearPending = db.prepareStatement("DELETE FROM large_pending WHERE md=?");
                lookup = db.prepareStatement("SELECT d.stamp,d.id,d.title,r.md FROM docs d LEFT JOIN document_roles r ON r.md=d.md WHERE d.md=?");
                remove = db.prepareStatement("DELETE FROM docs WHERE md=?");
                doc = db.prepareStatement("INSERT INTO docs(md,stamp,source,title) VALUES(?,?,?,?) RETURNING id");
                line = db.prepareStatement("INSERT INTO lines(doc,kind,section,text,folded) VALUES(?,?,?,?,?)");
                picture = db.prepareStatement("INSERT INTO pictures(doc,path,location,section,text,folded) VALUES(?,?,?,?,?,?)");
            } catch (SQLException | IOException | RuntimeException e) {
                try { db.close(); } catch (SQLException close) { e.addSuppressed(close); }
                throw e;
            }
        }

        private void importLegacy() throws SQLException {
            Path old = library.resolve(DATABASE);
            if (old.equals(database()) || !Files.isRegularFile(old)) return;
            try (var check = db.prepareStatement("SELECT imported FROM folders WHERE path=?")) {
                check.setString(1, library.toString());
                try (var rows = check.executeQuery()) { if (rows.next() && rows.getInt(1) != 0) return; }
            }
            try (var attach = db.prepareStatement("ATTACH DATABASE ? AS legacy")) {
                attach.setString(1, old.toUri().toASCIIString() + "?mode=ro");
                attach.execute();
            }
            var tables = new HashSet<String>();
            try (var rows = sql.executeQuery("SELECT name FROM legacy.sqlite_master WHERE type='table'")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            // A copied index can still contain absolute paths from its original directory.
            sql.execute("CREATE TEMP TABLE incoming(id INTEGER PRIMARY KEY, md TEXT UNIQUE)");
            try (var select = db.prepareStatement("INSERT INTO incoming SELECT id,md FROM legacy.docs WHERE substr(md,1,length(?1))=?1 AND instr(substr(md,length(?1)+1),?2)=0 AND md NOT IN (SELECT md FROM main.docs)")) {
                String prefix = library.toString();
                select.setString(1, prefix.endsWith(File.separator) ? prefix : prefix + File.separator);
                select.setString(2, File.separator);
                select.executeUpdate();
            }
            sql.executeUpdate("INSERT INTO main.docs(md,stamp,source,title) SELECT d.md,stamp,source,title FROM legacy.docs d JOIN incoming i ON i.id=d.id");
            String join = " JOIN incoming i ON i.id=v.doc JOIN main.docs d ON d.md=i.md ORDER BY v.id";
            sql.executeUpdate("INSERT INTO main.lines(doc,kind,section,text,folded) SELECT d.id,v.kind,v.section,v.text,v.folded FROM legacy.lines v" + join);
            if (tables.contains("pictures"))
                sql.executeUpdate("INSERT INTO main.pictures(doc,path,location,section,text,folded) SELECT d.id,v.path,v.location,v.section,v.text,v.folded FROM legacy.pictures v" + join);
            for (String table : List.of("large_allowed","large_pending")) {
                if (tables.contains(table)) {
                    try (var rows = sql.executeQuery("SELECT * FROM legacy." + table);
                         var insert = db.prepareStatement("INSERT OR IGNORE INTO main." + table +
                                 (table.equals("large_pending") ? " VALUES(?,?)" : " VALUES(?)"))) {
                        while (rows.next()) {
                            Path path = Path.of(rows.getString(1));
                            if (!library.equals(path.getParent())) continue;
                            insert.setString(1, path.toString());
                            if (table.equals("large_pending")) insert.setLong(2, rows.getLong(2));
                            insert.executeUpdate();
                        }
                    }
                }
            }
            String hold = tables.contains("library_options") ?
                    ",hold_large=coalesce((SELECT value FROM legacy.library_options WHERE name='hold_large'),1)" : "";
            try (var done = db.prepareStatement("UPDATE folders SET imported=1" + hold + " WHERE path=?")) {
                done.setString(1, library.toString());
                done.executeUpdate();
            }
        }

        private static void enableWal(Statement sql) throws SQLException, IOException {
            long start = System.nanoTime();
            for (;;) {
                checkInterrupted();
                try {
                    try (var mode = sql.executeQuery("PRAGMA journal_mode")) {
                        if (mode.next() && "wal".equalsIgnoreCase(mode.getString(1))) return;
                    }
                    sql.execute("PRAGMA journal_mode=WAL");
                    return;
                } catch (SQLException e) {
                    int primary = e.getErrorCode() & 0xff;
                    if ((primary != SQLiteErrorCode.SQLITE_BUSY.code && primary != SQLiteErrorCode.SQLITE_LOCKED.code)
                            || TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= WRITER_WAIT_MS) throw e;
                    // Switching journal modes can return BUSY immediately when another connection opens this index.
                    try { Thread.sleep(25); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("색인 갱신을 중단했습니다.");
                    }
                }
            }
        }

        List<String> pathsAfter(String after) throws SQLException {
            var paths = new ArrayList<String>(INDEX_BATCH);
            try (var select = db.prepareStatement("SELECT md FROM docs WHERE md > ?1 UNION SELECT md FROM large_pending WHERE md > ?1 UNION SELECT md FROM large_allowed WHERE md > ?1 ORDER BY md LIMIT ?2")) {
                select.setString(1, after);
                select.setInt(2, INDEX_BATCH);
                try (var rows = select.executeQuery()) {
                    while (rows.next()) paths.add(rows.getString(1));
                }
            }
            return paths;
        }

        BatchResult write(List<Path> notes, boolean removeOnly) throws SQLException, IOException {
            checkInterrupted();
            int indexed = 0, updated = 0, removed = 0, deferred = 0;
            sql.execute("BEGIN IMMEDIATE");
            try {
                boolean hold = holdLarge();
                for (Path note : notes) {
                    checkInterrupted();
                    Outcome outcome = removeOnly ? removeMissing(note) : index(note, hold);
                    if (outcome == Outcome.UNCHANGED || outcome == Outcome.UPDATED) indexed++;
                    if (outcome == Outcome.UPDATED) updated++;
                    if (outcome == Outcome.DEFERRED) deferred++;
                    if (outcome != Outcome.DEFERRED && outcome != Outcome.SKIPPED) clearPending(note);
                    if (outcome == Outcome.REMOVED) removed++;
                }
                checkInterrupted();
                sql.execute("COMMIT");
                return new BatchResult(indexed, updated, removed, deferred);
            } catch (SQLException | IOException | RuntimeException e) {
                try { sql.execute("ROLLBACK"); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }

        private boolean holdLarge() throws SQLException {
            try (var query = db.prepareStatement("SELECT hold_large FROM folders WHERE path=?")) {
                query.setString(1, library.toString());
                try (var rows = query.executeQuery()) { return !rows.next() || rows.getInt(1) != 0; }
            }
        }

        private boolean allowed(Path file) throws SQLException {
            try (var query = db.prepareStatement("SELECT 1 FROM large_allowed WHERE md=?")) {
                query.setString(1, file.toString());
                try (var row = query.executeQuery()) { return row.next(); }
            }
        }

        private void clearPending(Path file) throws SQLException {
            clearPending.setString(1, file.toString());
            clearPending.executeUpdate();
        }

        private Outcome removeMissing(Path note) throws SQLException {
            if (library.equals(note.getParent()) && (Files.notExists(note) || Files.isDirectory(note))) {
                clearPending(note);
                try (var clear = db.prepareStatement("DELETE FROM large_allowed WHERE md=?")) {
                    clear.setString(1, note.toString());
                    clear.executeUpdate();
                }
                try(var clear=db.prepareStatement("DELETE FROM document_roles WHERE md=?")){
                    clear.setString(1,note.toString());clear.executeUpdate();
                }
                return delete(note);
            }
            return Outcome.SKIPPED;
        }

        private Outcome delete(Path note) throws SQLException {
            remove.setString(1, note.toString());
            return remove.executeUpdate() == 0 ? Outcome.SKIPPED : Outcome.REMOVED;
        }

        private Outcome index(Path file, boolean hold) throws SQLException, IOException {
            var attrs = attributes(file);
            if (attrs == null) return removeMissing(file);
            if (!attrs.isRegularFile()) return removeMissing(file);
            String stamp = stamp(attrs);

            lookup.setString(1, file.toString());
            try (var row = lookup.executeQuery()) {
                if (row.next()) {
                    String[] previous = row.getString(1).split("\n", 3);
                    if (previous.length == 3 && stamp.equals(previous[0])
                            && PictureAssets.manifestStamp(file, previous[1]).equals(previous[2])) {
                        if(row.getString(4)==null)inferRole(file,row.getLong(2),row.getString(3));
                        return Outcome.UNCHANGED;
                    }
                }
            }

            if (hold && attrs.size() > LARGE_BYTES && !allowed(file)) {
                try (var mark = db.prepareStatement("INSERT INTO large_pending VALUES(?,?) ON CONFLICT(md) DO UPDATE SET size=excluded.size WHERE size<>excluded.size")) {
                    mark.setString(1, file.toString());
                    mark.setLong(2, attrs.size());
                    mark.executeUpdate();
                }
                return Outcome.DEFERRED;
            }

            if (attrs.size() <= LARGE_BYTES) clearPending(file);
            // A converter may publish another generation while the body is being read.
            sql.execute("SAVEPOINT document_update");
            Outcome outcome = delete(file);
            try (var in = Files.newBufferedReader(file)) {
                String heading = in.readLine();
                in.readLine();
                Path source = original(in.readLine());
                if (heading != null && heading.startsWith("# ") && source != null) {
                    String generation = null, value;
                    while ((value = in.readLine()) != null && !value.startsWith("## ")) {
                        checkInterrupted();
                        if (generation == null) generation = PictureAssets.generation(value);
                    }
                    String manifestStamp = PictureAssets.manifestStamp(file, generation);
                    writeNote(file, stamp, source, heading.substring(2), generation, manifestStamp, in, value);
                    outcome = manifestStamp.equals(PictureAssets.manifestStamp(file, generation))
                            ? Outcome.UPDATED : Outcome.SKIPPED;
                }
            } catch (NoSuchFileException e) {
                outcome = Outcome.SKIPPED;
            }
            var after = attributes(file);
            if (after == null || !stamp.equals(stamp(after)) || !attrs.creationTime().equals(after.creationTime()))
                outcome = Outcome.SKIPPED;
            if (outcome == Outcome.SKIPPED) sql.execute("ROLLBACK TO document_update");
            sql.execute("RELEASE document_update");
            return outcome;
        }

        private void writeNote(Path file, String stamp, Path source, String title, String generation,
                               String manifestStamp, BufferedReader in, String value) throws SQLException, IOException {
            doc.setString(1, file.toString());
            doc.setString(2, stamp + "\n" + Objects.toString(generation, "") + "\n" + manifestStamp);
            doc.setString(3, source.toString());
            doc.setString(4, title);
            long id;
            try (var row = doc.executeQuery()) {
                row.next();
                id = row.getLong(1);
            }
            add(line, id, 0, "제목", title);
            add(line, id, 1, "원본 경로", source.toString());

            Map<String, PictureAssets.Entry> images = new HashMap<>();
            String imagePrefix = PictureAssets.prefix(PictureAssets.key(file), generation);
            for (var entry : PictureAssets.entries(file, generation)) {
                var reference = new PictureAssets.Reference(imagePrefix + entry.filename(), entry.location());
                images.put(PictureAssets.markdownLine(reference), entry);
            }
            String section = "";
            for (; value != null; value = in.readLine()) {
                checkInterrupted();
                if (value.startsWith("## ")) section = value.substring(3);
                if (section.isEmpty() || value.isBlank()) continue;
                var image = images.get(value);
                if (image != null && section.equals(image.section())) {
                    images.remove(value);
                    picture.setLong(1, id);
                    picture.setString(2, imagePrefix + image.filename());
                    picture.setString(3, image.location());
                    picture.setString(4, section);
                    picture.setString(5, image.text());
                    picture.setString(6, image.text().toLowerCase(Locale.ROOT));
                    picture.executeUpdate();
                    continue;
                }
                if (value.startsWith("| ")) value = value.replace("<br>", " ")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                        .replace("\\|", "|").replace("\\\\", "\\");
                add(line, id, 2, section, value);
            }
            inferRole(file,id,title);
        }

        private void inferRole(Path note,long id,String title)throws SQLException{
            var kind=PmReference.classify(title);
            if(kind==null){
                try(var q=db.prepareStatement("SELECT section,text FROM lines WHERE doc=? AND kind=2 ORDER BY id LIMIT 80")){
                    q.setLong(1,id);
                    try(var rows=q.executeQuery()){
                        while(kind==null && rows.next()){
                            kind=PmReference.classify(rows.getString(1));
                            String heading=rows.getString(2);
                            if(kind==null && heading.startsWith("### "))kind=PmReference.classify(heading);
                        }
                    }
                }
            }
            try(var q=db.prepareStatement("INSERT INTO document_roles(md,inferred) VALUES(?,?) ON CONFLICT(md) DO UPDATE SET inferred=excluded.inferred")){
                q.setString(1,note.toString());q.setString(2,kind==null?"":kind.name());q.executeUpdate();
            }
        }

        @Override public void close() throws SQLException {
            db.close();
        }
    }

    private static BasicFileAttributes attributes(Path file) throws IOException {
        try { return Files.readAttributes(file, BasicFileAttributes.class); }
        catch (NoSuchFileException e) { return null; }
    }

    private static String stamp(BasicFileAttributes attrs) {
        return attrs.lastModifiedTime() + "/" + attrs.size() + "/" + attrs.fileKey();
    }

    private static Path original(String link) {
        String prefix = "[원본 파일 열기](<";
        if (link == null || !link.startsWith(prefix) || !link.endsWith(">)")) return null;
        try {
            Path source = Path.of(URI.create(link.substring(prefix.length(), link.length() - 2)));
            return source.getFileName() != null && Library.supported(source) ? source : null;
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }

    private static void add(PreparedStatement stmt, long id, int kind, String section, String text) throws SQLException {
        stmt.setLong(1,id); stmt.setInt(2,kind); stmt.setString(3,section);
        stmt.setString(4,text); stmt.setString(5,text.toLowerCase(Locale.ROOT)); stmt.executeUpdate();
    }

    record Evidence(int candidate, String term, String field, String quote) {}

    static List<Evidence> evidence(Path library,List<Library.Hit> hits,List<String> terms,boolean includeTitles) throws IOException {
        if(hits.isEmpty()) return List.of();
        var candidates=new com.google.gson.JsonArray();
        for(var hit:hits){
            var item=new com.google.gson.JsonObject();
            item.addProperty("md",hit.md().toString()); item.addProperty("section",hit.section());
            if(hit.picture()!=null)item.addProperty("picture",hit.picture().relativePath());
            candidates.add(item);
        }
        String sql="""
                WITH candidates AS (
                    SELECT j.key AS candidate,d.id AS doc,j.value->>'section' AS section,
                           j.value->>'picture' AS picture FROM json_each(?) j JOIN visible_docs d ON d.md=j.value->>'md'
                ), words AS (SELECT key AS ord,value AS word FROM json_each(?)),
                fields AS (
                    SELECT c.candidate,l.id,l.text,l.folded,
                           CASE l.kind WHEN 0 THEN '문서명' ELSE '구역 원문' END AS field
                    FROM candidates c JOIN lines l ON l.doc=c.doc
                    WHERE c.picture IS NULL AND ((l.kind=2 AND c.section=l.section) OR (? AND l.kind=0))
                    UNION ALL
                    SELECT c.candidate,p.id,p.text,p.folded,'그림 OCR'
                    FROM candidates c JOIN pictures p ON p.doc=c.doc AND p.path=c.picture
                ), matches AS (
                    SELECT candidate,ord,word,field,
                           substr(text,max(1,instr(folded,word)-30),min(30,instr(folded,word)-1)+length(word)+45) AS quote,
                           row_number() OVER(PARTITION BY candidate,ord,field ORDER BY id) AS choice
                    FROM fields JOIN words ON instr(folded,word)>0
                ) SELECT candidate,word,field,quote FROM matches WHERE choice=1 ORDER BY candidate,ord,field
                """;
        return readIndex(db->{
            try(var stmt=db.prepareStatement(sql)){
                stmt.setString(1,candidates.toString());
                stmt.setString(2,new com.google.gson.Gson().toJson(terms.stream().map(t->t.toLowerCase(Locale.ROOT)).distinct().limit(8).toList()));
                stmt.setBoolean(3,includeTitles);
                var rows=new ArrayList<Evidence>();
                try(var rs=stmt.executeQuery()){
                    while(rs.next())rows.add(new Evidence(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4)));
                }
                return rows;
            }
        });
    }

    static String documentRole(Path md)throws IOException{
        var values=readIndex(db->{
            try(var q=db.prepareStatement("SELECT role FROM visible_docs WHERE md=?")){
                q.setString(1,md.toString());
                try(var r=q.executeQuery()){return r.next()?List.of(r.getString(1)):List.<String>of();}
            }
        });
        if(values.isEmpty() || values.getFirst().isBlank() || values.getFirst().equals("UNKNOWN"))return "미분류";
        return PmReference.Kind.valueOf(values.getFirst()).label;
    }

    static void setDocumentRole(Path md,String selection)throws IOException{
        String role=null;
        if(!selection.equals("자동 분류")){
            role=Arrays.stream(PmReference.Kind.values()).filter(k->k.label.equals(selection))
                    .map(Enum::name).findFirst().orElse("UNKNOWN");
        }
        try(var writer=new IndexWriter(md.getParent());
            var q=writer.db.prepareStatement("INSERT INTO document_roles(md,inferred,manual) SELECT md,'',? FROM docs WHERE md=? ON CONFLICT(md) DO UPDATE SET manual=excluded.manual")){
            q.setString(1,role);q.setString(2,md.toString());
            if(q.executeUpdate()!=1)throw new IOException("색인에서 문서를 찾지 못했습니다.");
        }catch(SQLException e){throw new IOException(e);}
    }

    static List<DocumentAgent.Claim> pmReferences(Path root,PmReference plan,Focus focus)throws IOException {
        return pmReferences(root,plan,focus,"");
    }

    static List<DocumentAgent.Claim> pmReferences(Path root,PmReference plan,Focus focus,String question)throws IOException {
        return readIndex(db->plan.search(db,focus,question));
    }

    record Focus(Path md, String section) {}

    static List<Library.Hit> focusedDocument(Path root, Focus focus) throws IOException {
        return readIndex(db -> {
            try(var stmt=db.prepareStatement("SELECT md,source,title,'','' FROM visible_docs WHERE md=?")){
                stmt.setString(1,focus.md().toString());
                return results(stmt);
            }
        });
    }

    static List<Library.Hit> agentCandidates(Path library,List<String> terms,boolean pictures,Focus focus) throws IOException {
        if(focus==null) return agentCandidates(library,terms,pictures);
        var words=terms.stream().map(w->w.strip().toLowerCase(Locale.ROOT)).filter(w->!w.isEmpty()).distinct().limit(8).toList();
        if(words.isEmpty())return List.of();
        return readIndex(db->{
            String table=pictures ? "pictures" : "lines";
            String target=pictures ? "p.id" : "p.section";
            String sql="SELECT d.md,d.source,d.title,p.section,substr(p.text,1,2000)"
                    +(pictures?",p.path,p.location":"")
                    +" FROM visible_docs d JOIN "+table+" p ON p.doc=d.id JOIN json_each(?) w ON instr(p.folded,w.value)>0"
                    +" WHERE d.md=? AND (? IS NULL OR p.section=?)"+(pictures?"":" AND p.kind=2")
                    +" GROUP BY "+target+" ORDER BY count(DISTINCT w.value) DESC,min(p.id) LIMIT 50";
            try(var stmt=db.prepareStatement(sql)){
                stmt.setString(1,new com.google.gson.Gson().toJson(words));stmt.setString(2,focus.md().toString());
                stmt.setString(3,focus.section());stmt.setString(4,focus.section());
                if(!pictures)return results(stmt);
                var hits=new ArrayList<Library.Hit>();
                try(var rs=stmt.executeQuery()){while(rs.next())hits.add(pictureHit(rs));}
                return hits;
            }
        });
    }

    static List<Library.Hit> agentCandidates(Path library, List<String> terms, boolean pictures) throws IOException {
        var words = terms.stream().map(w -> w.strip().toLowerCase(Locale.ROOT))
                .filter(w -> !w.isEmpty()).distinct().limit(8).toList();
        if (words.isEmpty()) return List.of();
        return readIndex(db -> pictures
                ? findPictures(db, words, Mode.OR, 50)
                : findSections(db, words));
    }

    private static List<Library.Hit> findSections(Connection db, List<String> words) throws SQLException {
        String sql = """
                WITH words(word) AS (SELECT value FROM json_each(?)),
                matches AS (
                    SELECT l.id,l.doc,l.kind,l.section,w.word FROM words w CROSS JOIN terms
                    JOIN lines l ON l.id=terms.rowid
                    WHERE length(w.word)>=3 AND terms MATCH ('"' || replace(w.word,'"','""') || '"')
                      AND instr(l.folded,w.word)>0 AND l.kind IN (0,2)
                    UNION ALL
                    SELECT l.id,l.doc,l.kind,l.section,w.word FROM words w CROSS JOIN lines l
                    WHERE length(w.word)<3 AND instr(l.folded,w.word)>0 AND l.kind IN (0,2)
                ), sections AS (
                    SELECT doc,section,count(DISTINCT word) AS coverage,min(id) AS evidence
                    FROM matches WHERE kind=2 GROUP BY doc,section
                ), titles AS (
                    SELECT doc,count(DISTINCT word) AS coverage FROM matches WHERE kind=0 GROUP BY doc
                )
                , candidates AS (
                    SELECT s.doc,s.section,s.evidence,s.coverage,coalesce(t.coverage,0) AS title_score
                    FROM sections s LEFT JOIN titles t ON t.doc=s.doc
                    UNION ALL
                    SELECT t.doc,'제목',min(m.id),0,t.coverage
                    FROM titles t JOIN matches m ON m.doc=t.doc AND m.kind=0
                    WHERE NOT EXISTS (SELECT 1 FROM sections s WHERE s.doc=t.doc)
                    GROUP BY t.doc
                )
                SELECT d.md,d.source,d.title,c.section,
                       substr(l.text,max(1,instr(l.folded,m.word)-80),240)
                FROM candidates c JOIN lines l ON l.id=c.evidence
                JOIN matches m ON m.id=c.evidence JOIN visible_docs d ON d.id=c.doc
                GROUP BY c.doc,c.section
                ORDER BY c.coverage+c.title_score DESC,c.coverage DESC,d.title,d.md,c.evidence
                LIMIT 50
                """;
        try (var stmt = db.prepareStatement(sql)) {
            stmt.setString(1,new com.google.gson.Gson().toJson(words));
            return results(stmt);
        }
    }

    private static List<Library.Hit> findWords(Connection db, List<String> words, Scope scope, Mode mode, int limit) throws SQLException {
        // Match within the selected fields, then qualify documents before limiting result lines.
        String sql = """
            WITH words(word) AS (SELECT value FROM json_each(?)),
            matches AS (
                SELECT w.word, l.id, l.doc, l.kind FROM words w CROSS JOIN terms
                JOIN lines l ON l.id=terms.rowid
                WHERE length(w.word)>=3 AND terms MATCH ('"' || replace(w.word,'"','""') || '"')
                AND instr(l.folded,w.word)>0
                UNION ALL
                SELECT w.word, l.id, l.doc, l.kind FROM words w CROSS JOIN lines l
                WHERE length(w.word)<3 AND instr(l.folded,w.word)>0
            ), scoped AS (SELECT * FROM matches WHERE (?=-1 OR kind=?)),
            docs_found AS (SELECT doc,count(DISTINCT word) AS score FROM scoped GROUP BY doc HAVING count(DISTINCT word)>=?)
            """;
        if (mode == Mode.AND) sql += """
            , coverage AS (SELECT id,count(*) AS n FROM scoped GROUP BY id),
            evidence AS (
                SELECT s.doc,s.word,l.section,l.text,instr(l.folded,s.word) AS pos,c.n,
                       row_number() OVER(PARTITION BY s.doc,s.word ORDER BY c.n DESC,s.kind,s.id) AS choice
                FROM scoped s JOIN docs_found f ON f.doc=s.doc
                JOIN coverage c ON c.id=s.id JOIN lines l ON l.id=s.id
            )
            SELECT d.md,d.source,d.title,'모두 포함',
                   group_concat(e.word || '  ·  ' || e.section || char(10) ||
                       CASE WHEN e.pos>61 THEN '…' ELSE '' END ||
                       substr(e.text,max(1,e.pos-60),min(60,e.pos-1)+length(e.word)+100) ||
                       CASE WHEN length(e.text)>e.pos+length(e.word)+99 THEN '…' ELSE '' END,
                       char(10)||char(10) ORDER BY q.key)
            FROM evidence e JOIN visible_docs d ON d.id=e.doc JOIN json_each(?) q ON q.value=e.word
            WHERE e.choice=1 GROUP BY d.id ORDER BY max(e.n) DESC,d.title,d.md LIMIT ?
            """;
        else sql += """
            ,
            rows_found AS (
                SELECT s.id,s.doc,s.kind,count(*) AS coverage FROM scoped s
                WHERE s.kind<>1 OR NOT EXISTS (
                    SELECT 1 FROM scoped title WHERE title.doc=s.doc AND title.kind=0 AND title.word=s.word)
                GROUP BY s.id,s.doc,s.kind
            ), ranked AS (
                SELECT d.md,d.source,d.title,l.section,l.text,f.score,r.coverage,r.kind,l.id,
                       row_number() OVER(PARTITION BY r.doc ORDER BY r.coverage DESC,r.kind,l.id) AS occurrence
                FROM rows_found r JOIN docs_found f ON r.doc=f.doc JOIN visible_docs d ON d.id=r.doc JOIN lines l ON l.id=r.id)
            SELECT md,source,title,section,text FROM ranked
            ORDER BY score DESC,occurrence,coverage DESC,kind,title,md,id LIMIT ?
            """;
        int kind = switch(scope) {
            case ALL -> -1;
            case TITLE -> 0;
            case CONTENT -> 2;
            case IMAGE -> throw new IllegalArgumentException("그림 검색 범위");
        };
        try (var stmt = db.prepareStatement(sql)) {
            String json = new com.google.gson.Gson().toJson(words);
            stmt.setString(1,json);
            stmt.setInt(2,kind); stmt.setInt(3,kind);
            stmt.setInt(4,mode==Mode.AND ? words.size() : 1);
            int next=5;
            if (mode==Mode.AND) stmt.setString(next++,json);
            stmt.setInt(next,limit);
            return results(stmt);
        }
    }

    private static List<Library.Hit> find(Connection db, String query, Scope scope, int limit) throws SQLException {
        if (query.isEmpty()) {
            try (var stmt = db.prepareStatement("SELECT md,source,title,'제목',title FROM visible_docs ORDER BY title,md LIMIT ?")) {
                stmt.setInt(1, limit);
                return results(stmt);
            }
        }
        String needle = query.toLowerCase(Locale.ROOT);
        boolean indexed = needle.codePointCount(0,needle.length()) >= 3;
        String filter = switch (scope) {
            case TITLE -> "kind=0";
            case CONTENT -> "kind=2";
            case ALL -> "(kind<>1 OR instr(lower(d.title),?)=0)";
            case IMAGE -> throw new IllegalArgumentException("그림 검색 범위");
        };
        String sql = """
                SELECT md,source,title,section,text FROM (
                    SELECT d.md,d.source,d.title,l.section,l.text,l.kind,l.id,
                           row_number() OVER(PARTITION BY l.doc,l.kind ORDER BY l.id) AS occurrence
                    FROM lines l JOIN visible_docs d ON d.id=l.doc WHERE
                """ + filter + (indexed ? " AND l.id IN (SELECT rowid FROM terms WHERE terms MATCH ?)" : "")
                + " AND instr(l.folded,?)>0) ORDER BY kind,occurrence,title,md,id LIMIT ?";
        try (var stmt = db.prepareStatement(sql)) {
            int i = 1;
            if (scope == Scope.ALL) stmt.setString(i++,needle);
            if (indexed) stmt.setString(i++,"\"" + needle.replace("\"","\"\"") + "\"");
            stmt.setString(i++,needle); stmt.setInt(i,limit);
            return results(stmt);
        }
    }

    private static List<Library.Hit> findPictures(Connection db, List<String> words, Mode mode, int limit) throws SQLException {
        String sql = """
            WITH words(word) AS (SELECT value FROM json_each(?)),
            matches AS (
                SELECT p.id FROM words w CROSS JOIN picture_terms
                JOIN pictures p ON p.id=picture_terms.rowid
                WHERE length(w.word)>=3 AND picture_terms MATCH ('"' || replace(w.word,'"','""') || '"')
                    AND instr(p.folded,w.word)>0
                UNION ALL
                SELECT p.id FROM words w CROSS JOIN pictures p
                WHERE length(w.word)<3 AND instr(p.folded,w.word)>0
            ), ranked AS (SELECT id,count(*) AS score FROM matches GROUP BY id HAVING count(*)>=?)
            SELECT d.md,d.source,d.title,p.section,p.text,p.path,p.location
            FROM ranked r JOIN pictures p ON p.id=r.id JOIN visible_docs d ON d.id=p.doc
            ORDER BY r.score DESC,d.title,d.md,p.id LIMIT ?
            """;
        List<Library.Hit> results = new ArrayList<>();
        try (var stmt = db.prepareStatement(sql)) {
            stmt.setString(1,new com.google.gson.Gson().toJson(words));
            stmt.setInt(2,mode == Mode.AND ? words.size() : 1);
            stmt.setInt(3,limit);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) results.add(pictureHit(rs));
            }
        }
        return results;
    }

    private static Library.Hit pictureHit(ResultSet rs) throws SQLException {
        var picture = new PictureAssets.Reference(rs.getString(6),rs.getString(7));
        return new Library.Hit(Path.of(rs.getString(1)),Path.of(rs.getString(2)),
                rs.getString(3),rs.getString(4),rs.getString(5),picture);
    }

    private static List<Library.Hit> results(PreparedStatement stmt) throws SQLException {
            List<Library.Hit> results = new ArrayList<>();
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) results.add(new Library.Hit(Path.of(rs.getString(1)),Path.of(rs.getString(2)),
                        rs.getString(3),rs.getString(4),rs.getString(5)));
            }
            return results;
    }
}
