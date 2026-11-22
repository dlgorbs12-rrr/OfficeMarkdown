import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Prepared page-level retrieval shared by the reference search entry points. */
final class ReferenceIndex {
    private static final int LIMIT = 20;
    private static final Pattern SECTION = Pattern.compile("슬라이드 ([1-9][0-9]{0,8})");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IMAGE_CAPTION = Pattern.compile("그림 [0-9]+ · 슬라이드 [0-9]+.*");
    private static final String IDENTITY = "d.md,d.source,d.title,p.section,p.number,v.hash,v.generation,v.note_stamp";
    private static final String VISIBLE = " FROM reference_pages p JOIN visible_docs d ON d.id=p.doc"
            + " JOIN reference_documents v ON v.doc=d.id";
    private static final Object LEGACY_PREPARATION = new Object();

    record Page(Library.Hit hit, int number, String version, String text, String sha256) {}
    record SearchResult(List<Page> pages, boolean unavailable) {}
    private record Key(Path source, int number, String hash) {}
    private record Indexed(Path note, Path source, String title, String section, int number,
                           String hash, String generation, String stamp) {
        Key key() { return new Key(source, number, hash); }
        String version() { return hash + "/" + generation + "/" + stamp; }
        Page page(String text) {
            String excerpt = text.strip().lines().map(String::strip).filter(line -> !line.isEmpty())
                    .findFirst().orElse("");
            if (excerpt.length() > 240) excerpt = excerpt.substring(0, 240) + "…";
            return new Page(new Library.Hit(note, source, title, section, excerpt), number, version(), text, hash);
        }
    }
    private record Ranked(Indexed indexed, long rowid) {}
    private record Scored(Ranked ranked, double score) {}
    private static final Comparator<Scored> ORDER = Comparator.comparingDouble(Scored::score).reversed()
            .thenComparing(s -> s.ranked.indexed.source.toString())
            .thenComparingInt(s -> s.ranked.indexed.number)
            .thenComparing(s -> s.ranked.indexed.hash);

    static boolean installed(Connection db) throws SQLException {
        return table(db, "reference_pages");
    }

    private static boolean table(Connection db, String name) throws SQLException {
        try (var q = db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            q.setString(1, name);
            try (var row = q.executeQuery()) { return row.next(); }
        }
    }

    private static String visibleDocuments(Connection db) throws SQLException {
        return "(SELECT d.* FROM docs d" + SearchIndex.visibilityPredicate(table(db, "large_pending")) + ")";
    }

    static void prepare() throws IOException {
        Path database = SearchIndex.database();
        if (!Files.isRegularFile(database)) return;
        if (!SearchIndex.readIndex(db -> List.of(installed(db))).contains(true)) install(database);
        prepareLegacy(database);
    }

    private static void install(Path database) throws IOException {
        try (var db = writer(database); var sql = db.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            if (!installed(db) && table(db, "docs")) {
                EvidenceIndex.schema(sql);
                sql.execute("CREATE INDEX IF NOT EXISTS reference_docs_source ON docs(source)");
                sql.execute("""
                        CREATE TABLE reference_documents (
                            doc INTEGER PRIMARY KEY REFERENCES docs ON DELETE CASCADE, hash TEXT NOT NULL,
                            generation TEXT, note_stamp TEXT NOT NULL)
                        """);
                sql.execute("""
                        CREATE TABLE reference_legacy_queue (
                            doc INTEGER PRIMARY KEY REFERENCES docs ON DELETE CASCADE, retry_after INTEGER NOT NULL DEFAULT 0)
                        """);
                sql.execute("""
                        CREATE TABLE reference_pages (
                            id INTEGER PRIMARY KEY, doc INTEGER NOT NULL REFERENCES docs ON DELETE CASCADE,
                            number INTEGER NOT NULL, section TEXT NOT NULL, text TEXT NOT NULL,
                            UNIQUE(doc,number))
                        """);
                sql.execute("""
                        CREATE TABLE reference_captions (
                            page INTEGER PRIMARY KEY REFERENCES reference_pages ON DELETE CASCADE,
                            text TEXT NOT NULL, model TEXT NOT NULL, prompt TEXT NOT NULL)
                        """);
                sql.execute("""
                        CREATE VIRTUAL TABLE reference_body_terms USING fts5(
                            heading, body, content='', contentless_delete=1, detail=full)
                        """);
                sql.execute("""
                        CREATE VIRTUAL TABLE reference_caption_terms USING fts5(
                            body, content='', contentless_delete=1, detail=full)
                        """);
                sql.execute("""
                        CREATE TRIGGER reference_body_delete AFTER DELETE ON reference_pages BEGIN
                            DELETE FROM reference_body_terms WHERE rowid=old.id;
                        END
                        """);
                sql.execute("""
                        CREATE TRIGGER reference_caption_delete AFTER DELETE ON reference_captions BEGIN
                            DELETE FROM reference_caption_terms WHERE rowid=old.page;
                        END
                        """);
                indexSelected(db, """
                        SELECT d.id FROM docs d JOIN evidence_version v ON v.doc=d.id
                        WHERE lower(d.source) LIKE '%.pptx' ORDER BY d.id
                        """);
                queueLegacy(db, """
                        SELECT d.id FROM docs d WHERE lower(d.source) LIKE '%.pptx'
                        AND NOT EXISTS (SELECT 1 FROM evidence_version v WHERE v.doc=d.id)
                        """);
            }
            // The schema and initial postings become visible together.
            sql.execute("COMMIT");
        } catch (SQLException failure) {
            throw new IOException("참고 장표 색인을 준비하지 못했습니다.", failure);
        }
    }

    private static Connection writer(Path database) throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var sql = db.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            try { db.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
        return db;
    }

    static void indexSelected(Connection db, String selection) throws SQLException, IOException {
        try (var q = db.createStatement(); var docs = q.executeQuery(selection); var insert = new PageWriter(db)) {
            while (docs.next()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("장표 색인 준비를 중단했습니다.");
                insert.writeConverted(docs.getLong(1));
            }
        }
    }

    static void indexDocument(Connection db, long doc) throws SQLException {
        try (var insert = new PageWriter(db)) { insert.writeConverted(doc); }
    }

    static void queueLegacy(Connection db, String selection) throws SQLException {
        try (var sql = db.createStatement()) {
            sql.executeUpdate("INSERT INTO reference_legacy_queue(doc) " + selection);
        }
    }

    static void queueLegacy(Connection db, long doc) throws SQLException {
        try (var q = db.prepareStatement("INSERT INTO reference_legacy_queue(doc) VALUES(?)")) {
            q.setLong(1,doc);
            q.executeUpdate();
        }
    }

    private record Legacy(long id, Path note, Path source, String indexedStamp) {}

    private static void prepareLegacy(Path database) throws IOException {
        synchronized (LEGACY_PREPARATION) {
            var sources = SearchIndex.readIndex(db -> {
                if (!installed(db)) return List.<Legacy>of();
                var result = new ArrayList<Legacy>();
                try (var q = db.createStatement(); var rows = q.executeQuery("""
                        SELECT d.id,d.md,d.source,d.stamp FROM reference_legacy_queue q
                        JOIN visible_docs d ON d.id=q.doc WHERE q.retry_after<=unixepoch()
                        ORDER BY d.id
                        """)) {
                    while (rows.next()) result.add(new Legacy(rows.getLong(1), Path.of(rows.getString(2)),
                            Path.of(rows.getString(3)), rows.getString(4)));
                }
                return result;
            });
            for (var source : sources) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("장표 색인 준비를 중단했습니다.");
                String generation, stamp;
                ReferenceOriginal.Content content;
                try {
                    generation = PictureAssets.currentGeneration(source.note);
                    stamp = SearchIndex.noteStamp(source.note, generation);
                    if (stamp == null) throw new IOException("변환 문서를 읽을 수 없습니다.");
                    content = ReferenceOriginal.read(source.source);
                } catch (IOException | RuntimeException error) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("장표 색인 준비를 중단했습니다.");
                    retryLegacy(database, source);
                    continue;
                }
                publishLegacy(database, source, generation, stamp, content);
            }
        }
    }

    private static void retryLegacy(Path database, Legacy source) throws IOException {
        try (var db = writer(database); var q = db.prepareStatement("""
                UPDATE reference_legacy_queue SET retry_after=unixepoch()+300 WHERE doc=?
                AND EXISTS (SELECT 1 FROM docs d WHERE d.id=doc AND d.md=? AND d.source=? AND d.stamp IS ?)
                """)) {
            bindLegacy(q, source);
            q.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("기존 장표 준비 상태를 기록하지 못했습니다.", error);
        }
    }

    private static void bindLegacy(PreparedStatement q, Legacy source) throws SQLException {
        q.setLong(1, source.id);
        q.setString(2, source.note.toString());
        q.setString(3, source.source.toString());
        q.setString(4, source.indexedStamp);
    }

    private static void publishLegacy(Path database, Legacy source, String generation, String stamp,
                                      ReferenceOriginal.Content content) throws IOException {
        try (var db = writer(database); var sql = db.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            try (var q = db.prepareStatement("""
                    DELETE FROM reference_legacy_queue WHERE doc=? AND EXISTS (
                        SELECT 1 FROM %s d WHERE d.id=doc AND d.md=? AND d.source=? AND d.stamp IS ?)
                    """.formatted(visibleDocuments(db)))) {
                bindLegacy(q, source);
                if (q.executeUpdate() != 1) return;
            }
            if (!noteCurrent(source.note, generation, stamp)) return;
            try (var add = db.prepareStatement("INSERT INTO reference_documents(doc,hash,generation,note_stamp) VALUES(?,?,?,?)");
                 var insert = new PageWriter(db)) {
                add.setLong(1, source.id);
                add.setString(2, content.hash());
                add.setString(3, generation);
                add.setString(4, stamp);
                add.executeUpdate();
                insert.writeOriginal(source.id, content.pages());
            }
            sql.execute("COMMIT");
        } catch (SQLException error) {
            throw new IOException("원본에서 준비한 장표를 저장하지 못했습니다.", error);
        }
    }

    private static final class PageWriter implements AutoCloseable {
        private final PreparedStatement version;
        private final PreparedStatement lines;
        private final PreparedStatement pictures;
        private final PreparedStatement page;
        private final PreparedStatement postings;

        PageWriter(Connection db) throws SQLException {
            version = db.prepareStatement("""
                    INSERT INTO reference_documents(doc,hash,generation,note_stamp)
                    SELECT d.id,v.hash,v.generation,d.stamp FROM docs d JOIN evidence_version v ON v.doc=d.id
                    WHERE d.id=?
                    """);
            lines = db.prepareStatement("SELECT section,text FROM lines WHERE doc=? AND kind=2 ORDER BY id");
            pictures = table(db, "pictures") ?
                    db.prepareStatement("SELECT section,text FROM pictures WHERE doc=? AND text<>'' ORDER BY id") : null;
            page = db.prepareStatement("INSERT INTO reference_pages(doc,number,section,text) VALUES(?,?,?,?) RETURNING id");
            postings = db.prepareStatement("INSERT INTO reference_body_terms(rowid,heading,body) VALUES(?,?,?)");
        }

        void writeConverted(long doc) throws SQLException {
            version.setLong(1, doc);
            if (version.executeUpdate() != 1) return;
            var pages = new LinkedHashMap<Integer, PageText>();
            lines.setLong(1, doc);
            try (var rows = lines.executeQuery()) {
                String section = null;
                PageText text = null;
                while (rows.next()) {
                    String next = rows.getString(1);
                    if (!Objects.equals(next, section)) {
                        section = next;
                        var match = SECTION.matcher(section);
                        text = match.matches() ? pages.computeIfAbsent(Integer.parseInt(match.group(1)),
                                ignored -> new PageText(next)) : null;
                    }
                    if (text != null) text.add(rows.getString(2));
                }
            }
            if (pictures != null) {
                pictures.setLong(1, doc);
                try (var rows = pictures.executeQuery()) {
                    while (rows.next()) {
                        var match = SECTION.matcher(rows.getString(1));
                        if (!match.matches()) continue;
                        var text = pages.get(Integer.parseInt(match.group(1)));
                        if (text != null) text.addOcr(rows.getString(2));
                    }
                }
            }
            for (var entry : pages.entrySet()) save(doc, entry.getKey(), entry.getValue());
        }

        void writeOriginal(long doc, Map<Integer, String> pages) throws SQLException {
            for (var entry : pages.entrySet()) {
                var text = new PageText("슬라이드 " + entry.getKey());
                entry.getValue().lines().forEach(text::add);
                save(doc, entry.getKey(), text);
            }
        }

        private void save(long doc, int number, PageText text) throws SQLException {
            page.setLong(1, doc);
            page.setInt(2, number);
            page.setString(3, text.section);
            page.setString(4, text.display.toString());
            try (var row = page.executeQuery()) {
                row.next();
                postings.setLong(1, row.getLong(1));
            }
            postings.setString(2, terms(text.heading.toString()));
            postings.setString(3, terms(text.body.toString()));
            postings.executeUpdate();
        }

        @Override public void close() throws SQLException {
            try (version; lines; pictures; page; postings) { /* The statements belong to this writer. */ }
        }
    }

    private static final class PageText {
        private final String section;
        private final StringBuilder display = new StringBuilder();
        private final StringBuilder heading = new StringBuilder();
        private final StringBuilder body = new StringBuilder();
        private boolean endOfShapes;

        PageText(String section) { this.section = section; }

        void add(String line) {
            if (line.startsWith("발표 노트 · ")) endOfShapes = true;
            if (endOfShapes || line.equals("## " + section) || IMAGE_CAPTION.matcher(line).matches()) return;
            boolean title = line.startsWith("### ");
            String text = title ? line.substring(4) : line;
            display.append(text).append('\n');
            (title ? heading : body).append(text).append('\n');
        }

        void addOcr(String text) {
            display.append(text).append('\n');
            body.append(text).append('\n');
        }
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String terms(String text) {
        var out = new StringBuilder();
        var words = WORD.matcher(normalize(text));
        while (words.find()) {
            String word = words.group();
            out.append('w');
            word.codePoints().forEach(point -> out.append(Integer.toHexString(point)).append('x'));
            out.append(' ');
            if (word.length() > 1) grams(out, word).append(' ');
        }
        return out.toString();
    }

    private static StringBuilder grams(StringBuilder out, String word) {
        for (int i = 0; i < word.length();) {
            int left = word.codePointAt(i);
            i += Character.charCount(left);
            if (i == word.length()) break;
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') out.append(' ');
            out.append('g').append(Integer.toHexString(left)).append('x')
                    .append(Integer.toHexString(word.codePointAt(i)));
        }
        return out;
    }

    private static String match(String query) {
        var match = new StringJoiner(" OR ");
        for (String word : SlideSearch.referenceTerms(normalize(query))) {
            if (word.length() > 128) continue;
            var exact = new StringBuilder("w");
            word.codePoints().forEach(point -> exact.append(Integer.toHexString(point)).append('x'));
            match.add(exact.toString());
            var partial = grams(new StringBuilder(), word);
            // Whole-token postings separate the gram runs of adjacent tokens.
            if (!partial.isEmpty()) match.add("\"" + partial + "\"");
        }
        return match.toString();
    }

    static List<Page> search(String query, SearchIndex.Focus focus) throws IOException {
        return searchDetailed(query, focus).pages();
    }

    static SearchResult searchDetailed(String query, SearchIndex.Focus focus) throws IOException {
        String match = match(query);
        var empty = new SearchResult(List.of(), false);
        if (match.isEmpty()) return empty;
        var results = SearchIndex.readIndex(db -> {
            db.setAutoCommit(false);
            if (!installed(db)) return List.of();
            var files = new LiveFiles();
            var scores = new HashMap<Key, Scored>();
            addRanks(scores, ranked(db, "reference_body_terms", "bm25(reference_body_terms,6.0,1.0)",
                    match, focus, files));
            addRanks(scores, ranked(db, "reference_caption_terms", "bm25(reference_caption_terms)",
                    match, focus, files));
            var result = new ArrayList<Page>(LIMIT);
            try (var text = db.prepareStatement("SELECT text FROM reference_pages WHERE id=?")) {
                for (var scored : scores.values().stream().sorted(ORDER).limit(LIMIT).toList()) {
                    var rank = scored.ranked;
                    text.setLong(1, rank.rowid);
                    try (var row = text.executeQuery()) {
                        if (row.next()) result.add(rank.indexed.page(row.getString(1)));
                    }
                }
            }
            return List.of(new SearchResult(List.copyOf(result), files.unavailable || pendingInScope(db,focus)));
        });
        return results.isEmpty() ? empty : results.getFirst();
    }

    private static boolean pendingInScope(Connection db, SearchIndex.Focus focus) throws SQLException {
        String sql = "SELECT 1 FROM reference_legacy_queue q JOIN visible_docs d ON d.id=q.doc"
                + (focus == null ? "" : " WHERE d.md=?") + " LIMIT 1";
        try (var statement = db.prepareStatement(sql)) {
            if (focus != null) statement.setString(1, focus.md().toAbsolutePath().normalize().toString());
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    private static void addRanks(Map<Key, Scored> scores, List<Ranked> ranks) {
        for (int i = 0; i < ranks.size(); i++) {
            var rank = ranks.get(i);
            var key = rank.indexed.key();
            double score = 1.0 / (11 + i);
            scores.merge(key, new Scored(rank, score), (a, b) -> new Scored(a.ranked, a.score + b.score));
        }
    }

    private static List<Ranked> ranked(Connection db, String table, String score, String match,
                                       SearchIndex.Focus focus, LiveFiles files) throws SQLException {
        String restrict = focus == null ? "" : " AND d.md=?" + (focus.section() == null ? "" : " AND p.section=?");
        String sql = "SELECT " + IDENTITY + ",p.id" + VISIBLE + " JOIN " + table + " ON " + table + ".rowid=p.id"
                + " WHERE " + table + " MATCH ?" + restrict
                + " ORDER BY " + score + ",d.source,p.number,v.hash,d.md";
        var best = new ArrayList<Ranked>(LIMIT);
        var seen = new HashSet<Key>();
        try (var q = db.prepareStatement(sql)) {
            q.setString(1, match);
            if (focus != null) {
                q.setString(2, focus.md().toAbsolutePath().normalize().toString());
                if (focus.section() != null) q.setString(3, focus.section());
            }
            try (var rows = q.executeQuery()) {
                while (best.size() < LIMIT && !Thread.currentThread().isInterrupted() && rows.next()) {
                    var item = indexed(rows);
                    if (!seen.contains(item.key()) && files.current(item)) {
                        seen.add(item.key());
                        best.add(new Ranked(item, rows.getLong(9)));
                    }
                }
            }
        }
        return best;
    }

    private static Indexed indexed(ResultSet r) throws SQLException {
        return new Indexed(Path.of(r.getString(1)), Path.of(r.getString(2)), r.getString(3),
                r.getString(4), r.getInt(5), r.getString(6), r.getString(7), r.getString(8));
    }

    private static final class LiveFiles {
        private final Map<Path, String> hashes = new HashMap<>();
        private final Map<Path, Boolean> notes = new HashMap<>();
        private boolean unavailable;

        boolean current(Indexed indexed) {
            boolean current = notes.computeIfAbsent(indexed.note, ignored -> noteCurrent(indexed))
                    && indexed.hash.equals(hashes.computeIfAbsent(indexed.source, LiveFiles::hash));
            if (!current) unavailable = true;
            return current;
        }

        private static String hash(Path source) {
            try { return EvidenceIndex.sourceHash(source); }
            catch (IOException | SecurityException unavailable) { return ""; }
        }
    }

    private static boolean noteCurrent(Indexed indexed) {
        return noteCurrent(indexed.note, indexed.generation, indexed.stamp);
    }

    private static boolean noteCurrent(Path note, String generation, String stamp) {
        try {
            return Objects.equals(generation, PictureAssets.currentGeneration(note))
                    && stamp.equals(SearchIndex.noteStamp(note, generation));
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    static boolean current(Page page) {
        // Check identity again against the current visible transaction before serving an old handle.
        if (!page.sha256.equals(LiveFiles.hash(page.hit.source()))) return false;
        try {
            return !SearchIndex.readIndex(db -> {
                if (!installed(db)) return List.of();
                try (var q = db.prepareStatement("SELECT " + IDENTITY + VISIBLE
                        + " WHERE d.md=? AND d.source=? AND p.number=? AND p.section=?")) {
                    q.setString(1, page.hit.md().toString());
                    q.setString(2, page.hit.source().toString());
                    q.setInt(3, page.number);
                    q.setString(4, page.hit.section());
                    try (var row = q.executeQuery()) {
                        if (!row.next()) return List.of();
                        var indexed = indexed(row);
                        return indexed.version().equals(page.version) && noteCurrent(indexed) ? List.of(true) : List.of();
                    }
                }
            }).isEmpty();
        } catch (IOException unavailable) {
            return false;
        }
    }

    static List<Page> pages(Path source) throws IOException {
        return SearchIndex.readIndex(db -> {
            if (!installed(db)) return List.of();
            var selected = new ArrayList<Page>();
            var files = new LiveFiles();
            Path note = null;
            try (var q = db.prepareStatement("SELECT " + IDENTITY + ",p.text" + VISIBLE
                    + " WHERE d.source=? ORDER BY d.md,p.number")) {
                q.setString(1, source.toAbsolutePath().normalize().toString());
                try (var rows = q.executeQuery()) {
                    while (rows.next()) {
                        var item = indexed(rows);
                        if (note != null && !note.equals(item.note)) break;
                        if (!files.current(item)) continue;
                        note = item.note;
                        selected.add(item.page(rows.getString(9)));
                    }
                }
            }
            return List.copyOf(selected);
        });
    }

    static List<Path> pendingSources() throws IOException {
        return SearchIndex.readIndex(db -> {
            if (!installed(db)) return List.of();
            var result = new ArrayList<Path>();
            try (var q = db.createStatement(); var rows = q.executeQuery("""
                    SELECT DISTINCT d.source FROM visible_docs d
                    WHERE EXISTS (SELECT 1 FROM reference_pages p WHERE p.doc=d.id AND
                        NOT EXISTS (SELECT 1 FROM reference_captions c WHERE c.page=p.id))
                    ORDER BY d.source
                    """)) {
                while (rows.next()) result.add(Path.of(rows.getString(1)));
            }
            return List.copyOf(result);
        });
    }

    static boolean storeDescriptions(Path source, String sha256, Map<Integer, String> descriptions) throws IOException {
        return storeDescriptions(source, sha256, descriptions, "", "");
    }

    static boolean storeDescriptions(Path source, String sha256, Map<Integer, String> descriptions,
                                     String model, String promptVersion) throws IOException {
        if (!HASH.matcher(sha256).matches() || descriptions.isEmpty()) return false;
        var snapshot = Map.copyOf(descriptions);
        source = source.toAbsolutePath().normalize();
        Path database = SearchIndex.database();
        if (!Files.isRegularFile(database)) return false;
        try (var db = writer(database); var sql = db.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            if (!installed(db)) return false;
            var selected = new LinkedHashMap<Indexed, Long>();
            String candidates = "SELECT " + IDENTITY + ",p.id FROM reference_pages p JOIN "
                    + visibleDocuments(db) + " d ON d.id=p.doc"
                    + " JOIN reference_documents v ON v.doc=d.id WHERE d.source=? AND v.hash=?"
                    + " ORDER BY d.md,p.number";
            try (var q = db.prepareStatement(candidates)) {
                q.setString(1, source.toString());
                q.setString(2, sha256);
                try (var rows = q.executeQuery()) {
                    while (rows.next()) selected.put(indexed(rows), rows.getLong(9));
                }
            }
            var notes = new LinkedHashMap<Path, Set<Integer>>();
            for (var item : selected.keySet())
                notes.computeIfAbsent(item.note, ignored -> new HashSet<>()).add(item.number);
            var verified = new HashMap<Path, Boolean>();
            selected.keySet().removeIf(item -> !verified.computeIfAbsent(item.note,
                    ignored -> snapshot.keySet().equals(notes.get(item.note)) && noteCurrent(item)));
            if (selected.isEmpty()) return false;
            try (var erase = db.prepareStatement("DELETE FROM reference_captions WHERE page=?");
                 var add = db.prepareStatement("INSERT INTO reference_captions(page,text,model,prompt) VALUES(?,?,?,?)");
                 var posting = db.prepareStatement("INSERT INTO reference_caption_terms(rowid,body) VALUES(?,?)")) {
                for (var entry : selected.entrySet()) {
                    long rowid = entry.getValue();
                    String caption = snapshot.get(entry.getKey().number);
                    erase.setLong(1, rowid);
                    erase.executeUpdate();
                    add.setLong(1, rowid);
                    add.setString(2, caption);
                    add.setString(3, model);
                    add.setString(4, promptVersion);
                    add.executeUpdate();
                    if (caption.isBlank()) continue;
                    posting.setLong(1, rowid);
                    posting.setString(2, terms(caption));
                    posting.executeUpdate();
                }
            }
            if (!sha256.equals(LiveFiles.hash(source))) return false;
            verified.clear();
            for (var item : selected.keySet())
                if (!verified.computeIfAbsent(item.note, ignored -> noteCurrent(item))) return false;
            sql.execute("COMMIT");
            return true;
            // A competing conversion invalidates the whole publication, including postings.
        } catch (SQLException failure) {
            throw new IOException("장표 설명 색인을 저장하지 못했습니다.", failure);
        }
    }
}
