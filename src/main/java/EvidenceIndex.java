import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Provenance of native relationships. Public references use note, generation and native locations. */
final class EvidenceIndex {
    private static final String HEADER = "<!-- office-source-sha256: ";
    enum State { NOT_VISIBLE, UNVERSIONED, PENDING, READY, STALE, MISSING }
    record Version(Path note, Path source, String hash, String generation, String extractor) {}
    record Edge(String kind, String from, String to, String fromLocation, String toLocation) {
        Edge {
            for (String value : List.of(kind, from, to, fromLocation, toLocation))
                if (value.isBlank()) throw new IllegalArgumentException("Blank relationship field");
        }
    }
    record Result(State state, Version version, List<Edge> edges) {}
    interface Extractor { List<Edge> read(Path snapshot) throws IOException; }
    static final class Changed extends IOException {
        Changed() { super("원본 또는 변환 세대가 변경되어 다시 읽어야 합니다."); }
    }

    static boolean supported(Path source) {
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx") || name.endsWith(".pptx");
    }

    static String hashHeader(String hash) { return HEADER + hash + " -->"; }
    static String parseHeader(String line) {
        if (line.length() != HEADER.length()+68 || !line.startsWith(HEADER) || !line.endsWith(" -->")) return null;
        String hash = line.substring(HEADER.length(), line.length()-4);
        return hash.matches("[0-9a-f]{64}") ? hash : null;
    }

    private static String copyDigest(Path source, OutputStream output) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        var before = Files.readAttributes(source,BasicFileAttributes.class);
        try (var in = Files.newInputStream(source)) {
            byte[] buffer = new byte[128 * 1024];
            for (int count; (count = in.read(buffer)) != -1;) {
                digest.update(buffer,0,count);
                output.write(buffer,0,count);
            }
        }
        var after = Files.readAttributes(source,BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !before.creationTime().equals(after.creationTime()) || !Objects.equals(before.fileKey(),after.fileKey()))
            throw new Changed();
        return HexFormat.of().formatHex(digest.digest());
    }

    record Snapshot(Path file, String hash) implements AutoCloseable {
        static Snapshot capture(Path source, Path folder) throws IOException {
            Path temp = Files.createTempFile(folder,".relation-source-",".tmp");
            try (var out = Files.newOutputStream(temp)) {
                return new Snapshot(temp,copyDigest(source,out));
            } catch (IOException | RuntimeException e) { Files.deleteIfExists(temp); throw e; }
        }
        void check(Path source) throws IOException {
            if (!hash.equals(copyDigest(source,OutputStream.nullOutputStream()))) throw new Changed();
        }
        public void close() throws IOException { Files.deleteIfExists(file); }
    }

    static void schema(Statement s) throws SQLException {
        s.execute("CREATE TABLE IF NOT EXISTS evidence_version(doc INTEGER PRIMARY KEY REFERENCES docs ON DELETE CASCADE, hash TEXT NOT NULL, generation TEXT NOT NULL, extractor TEXT)");
        s.execute("""
                CREATE TABLE IF NOT EXISTS evidence_edge(
                  doc INTEGER NOT NULL REFERENCES evidence_version ON DELETE CASCADE, kind TEXT NOT NULL,
                  src TEXT NOT NULL, dst TEXT NOT NULL, src_loc TEXT NOT NULL, dst_loc TEXT NOT NULL,
                  PRIMARY KEY(doc,kind,src,dst,src_loc,dst_loc)) WITHOUT ROWID""");
    }

    static void register(Connection db, long doc, String hash, String generation) throws SQLException {
        if (hash == null || generation == null) return;
        try (var s = db.prepareStatement("INSERT INTO evidence_version(doc,hash,generation) VALUES(?,?,?)")) {
            s.setLong(1,doc); s.setString(2,hash); s.setString(3,generation); s.executeUpdate();
        }
    }

    private record Row(Version version, Edge edge) {}
    private static List<Row> rows(Path note) throws IOException {
        return SearchIndex.readIndex(db -> {
            boolean migrated;
            try (var s = db.createStatement(); var rs = s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='evidence_version'")) {
                migrated = rs.next();
            }
            String fields = migrated ? "v.hash,v.generation,v.extractor,e.kind,e.src,e.dst,e.src_loc,e.dst_loc" :
                    "NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL";
            String joins = migrated ? " LEFT JOIN evidence_version v ON v.doc=d.id LEFT JOIN evidence_edge e ON e.doc=v.doc" : "";
            try (var s = db.prepareStatement("SELECT d.source,"+fields+" FROM visible_docs d"+joins+" WHERE d.md=?")) {
                s.setString(1,note.toString());
                var result = new ArrayList<Row>();
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        var version = new Version(note,Path.of(rs.getString(1)),rs.getString(2),rs.getString(3),rs.getString(4));
                        var edge = rs.getString(5)==null ? null : new Edge(rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9));
                        result.add(new Row(version,edge));
                    }
                }
                return result;
            }
        });
    }

    static Result read(Path markdown, String extractor) throws IOException {
        Path note = markdown.toAbsolutePath().normalize();
        var rows = rows(note);
        if (rows.isEmpty()) return new Result(State.NOT_VISIBLE,null,List.of());
        var v = rows.getFirst().version;
        State state;
        try {
            if (Files.notExists(note) || Files.notExists(v.source)) state = State.MISSING;
            else if (v.hash == null) state = State.UNVERSIONED;
            else if (!v.generation.equals(PictureAssets.currentGeneration(note)) ||
                    !v.hash.equals(copyDigest(v.source,OutputStream.nullOutputStream()))) state = State.STALE;
            else if (v.extractor == null) state = State.PENDING;
            else state = v.extractor.equals(extractor) ? State.READY : State.STALE;
        } catch (NoSuchFileException e) { state = State.MISSING; }
        catch (Changed e) { state = State.STALE; }
        var edges = state == State.READY ? rows.stream().map(Row::edge).filter(Objects::nonNull).toList() : List.<Edge>of();
        return new Result(state,v,edges);
    }

    /** Resolve a saved citation only against the exact source, conversion and extractor that produced it. */
    static Result read(Version citation) throws IOException {
        var current = read(citation.note,citation.extractor);
        if ((current.state == State.READY || current.state == State.PENDING || current.state == State.UNVERSIONED)
                && !citation.equals(current.version))
            return new Result(State.STALE,citation,List.of());
        return current;
    }

    /** Each explicitly selected document retains its identity. No title/identifier based scope expansion. */
    static Map<Path,Result> readSelection(Set<Path> selected, String extractor) throws IOException {
        var result = new LinkedHashMap<Path,Result>();
        for (Path p : selected) result.put(p,read(p,extractor));
        return Collections.unmodifiableMap(result);
    }

    static void extract(Path note, String extractorVersion, Extractor extractor) throws IOException {
        if (extractorVersion == null || extractorVersion.isBlank()) throw new IllegalArgumentException("Missing extractor version");
        for (int attempt=0; attempt<2; attempt++) {
            try { extractOnce(note,extractorVersion,extractor); return; }
            catch (Changed e) { if (attempt==1) throw e; }
        }
    }

    private static void extractOnce(Path note, String extractorVersion, Extractor extractor) throws IOException {
        var result = read(note,extractorVersion);
        if (result.state == State.READY) return;
        var v = result.version;
        if (v == null || v.hash == null || result.state == State.MISSING)
            throw new IOException("색인된 원본 버전이 필요합니다: "+result.state);
        try (var snapshot = Snapshot.capture(v.source,v.note.getParent())) {
            if (!v.hash.equals(snapshot.hash)) throw new Changed();
            var edges = List.copyOf(extractor.read(snapshot.file));
            publish(v,extractorVersion,edges,snapshot);
        }
    }

    private static void publish(Version v, String extractor, List<Edge> edges, Snapshot snapshot) throws IOException {
        try (var db = DriverManager.getConnection("jdbc:sqlite:"+SearchIndex.database()); var s = db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("BEGIN IMMEDIATE");
            // A competing writer may have replaced the document or published another extractor while we parsed.
            try (var check = db.prepareStatement("""
                    UPDATE evidence_version SET extractor=? WHERE hash=? AND generation=? AND extractor IS ?
                    AND doc=(SELECT id FROM docs WHERE md=? AND NOT EXISTS (SELECT 1 FROM large_pending WHERE md=?))
                    RETURNING doc""")) {
                check.setString(1,extractor); check.setString(2,v.hash); check.setString(3,v.generation);
                check.setString(4,v.extractor); check.setString(5,v.note.toString()); check.setString(6,v.note.toString());
                long id;
                try (var row = check.executeQuery()) {
                    if (!row.next()) {
                        if (!v.generation.equals(PictureAssets.currentGeneration(v.note))) throw new Changed();
                        throw new IOException("대기 중 색인 상태가 바뀌어 게시를 취소했습니다.");
                    }
                    id = row.getLong(1);
                }
                try (var del=db.prepareStatement("DELETE FROM evidence_edge WHERE doc=?")) {
                    del.setLong(1,id); del.executeUpdate();
                }
                try (var add=db.prepareStatement("INSERT INTO evidence_edge VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                    for (var e : edges) {
                        add.setLong(1,id); add.setString(2,e.kind); add.setString(3,e.from); add.setString(4,e.to);
                        add.setString(5,e.fromLocation); add.setString(6,e.toLocation); add.addBatch();
                    }
                    add.executeBatch();
                }
                snapshot.check(v.source);
                if (!v.generation.equals(PictureAssets.currentGeneration(v.note))) throw new Changed();
                s.execute("COMMIT");
            }
            // Closing an uncommitted SQLite connection rolls the entire replacement back.
        } catch (SQLException e) { throw new IOException("관계 근거 저장 실패",e); }
    }
}
