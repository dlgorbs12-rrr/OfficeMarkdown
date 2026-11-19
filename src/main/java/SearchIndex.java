import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;

final class SearchIndex {
    enum Mode {
        PHRASE("정확한 문구"), AND("모두 포함 (AND)"), OR("하나 이상 (OR)");
        private final String label;
        Mode(String label) { this.label = label; }
        public String toString() { return label; }
        List<String> words(String query) {
            String folded = query.strip().toLowerCase(Locale.ROOT);
            if (this == PHRASE || folded.isEmpty()) return List.of(folded);
            return Arrays.stream(folded.split("(?U)\\s+")).filter(s -> !s.isEmpty()).distinct().toList();
        }
    }
    enum Scope {
        ALL("전체"), TITLE("제목"), CONTENT("내용");
        private final String label;
        Scope(String label) { this.label = label; }
        public String toString() { return label; }
    }

    static Path defaultLibrary() {
        return Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
                "OfficeMarkdown", "Markdown");
    }

    static List<Library.Hit> search(Path root, String query, Scope scope, int limit) throws IOException {
        return search(root, query, scope, Mode.PHRASE, limit);
    }

    static List<Library.Hit> search(Path root, String query, Scope scope, Mode mode, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("검색 한도는 1 이상이어야 합니다.");
        Path library = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(library)) return List.of();
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + library.resolve(".office-index-v1.sqlite"));
             Statement sql = db.createStatement()) {
            sql.execute("PRAGMA busy_timeout=60000");
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("BEGIN IMMEDIATE");
            schema(sql);
            sync(db, library);
            sql.execute("COMMIT");
            var words = mode.words(query);
            if (words.size() <= 1) return find(db, words.isEmpty() ? "" : words.getFirst(), scope, limit);
            return findWords(db, words, scope, mode, limit);
        } catch (SQLException e) {
            throw new IOException("검색 색인을 열거나 갱신하지 못했습니다. " + e.getMessage(), e);
        }
    }

    private static void schema(Statement sql) throws SQLException {
        sql.execute("CREATE TABLE IF NOT EXISTS docs(id INTEGER PRIMARY KEY, md TEXT UNIQUE, stamp TEXT, source TEXT, title TEXT)");
        sql.execute("CREATE TABLE IF NOT EXISTS lines(id INTEGER PRIMARY KEY, doc INTEGER REFERENCES docs ON DELETE CASCADE, kind INTEGER, section TEXT, text TEXT, folded TEXT)");
        sql.execute("CREATE INDEX IF NOT EXISTS doc_lines ON lines(doc)");
        sql.execute("CREATE VIRTUAL TABLE IF NOT EXISTS terms USING fts5(folded,content='lines',content_rowid='id',tokenize='trigram')");
        sql.execute("CREATE TRIGGER IF NOT EXISTS terms_add AFTER INSERT ON lines BEGIN INSERT INTO terms(rowid,folded) VALUES(new.id,new.folded); END");
        sql.execute("CREATE TRIGGER IF NOT EXISTS terms_del AFTER DELETE ON lines BEGIN INSERT INTO terms(terms,rowid,folded) VALUES('delete',old.id,old.folded); END");
    }

    private static void sync(Connection db, Path library) throws SQLException, IOException {
        Map<String,String> existing = new HashMap<>();
        try (Statement stmt = db.createStatement(); ResultSet rs = stmt.executeQuery("SELECT md,stamp FROM docs")) {
            while (rs.next()) existing.put(rs.getString(1), rs.getString(2));
        }
        try (var files = Files.newDirectoryStream(library, p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"));
             var remove = db.prepareStatement("DELETE FROM docs WHERE md=?");
             var doc = db.prepareStatement("INSERT INTO docs(md,stamp,source,title) VALUES(?,?,?,?) RETURNING id");
             var line = db.prepareStatement("INSERT INTO lines(doc,kind,section,text,folded) VALUES(?,?,?,?,?)")) {
            for (Path file : files) {
                var attrs = Files.readAttributes(file, BasicFileAttributes.class);
                if (!attrs.isRegularFile()) continue;
                String name = file.toString();
                String stamp = attrs.lastModifiedTime() + "/" + attrs.size() + "/" + attrs.fileKey();
                if (stamp.equals(existing.remove(name))) continue;
                remove.setString(1, name); remove.executeUpdate();
                try (var in = Files.newBufferedReader(file)) {
                    String heading = in.readLine();
                    in.readLine();
                    String link = in.readLine(), prefix = "[원본 파일 열기](<";
                    if (heading == null || !heading.startsWith("# ") || link == null ||
                            !link.startsWith(prefix) || !link.endsWith(">)")) continue;
                    Path source;
                    try { source = Path.of(URI.create(link.substring(prefix.length(), link.length()-2))); }
                    catch (IllegalArgumentException | FileSystemNotFoundException e) { continue; }
                    if (!Library.supported(source)) continue;
                    String title = heading.substring(2);
                    doc.setString(1,name); doc.setString(2,stamp); doc.setString(3,source.toString()); doc.setString(4,title);
                    long id;
                    try (var rs = doc.executeQuery()) { rs.next(); id = rs.getLong(1); }
                    add(line,id,0,"제목",title);
                    add(line,id,1,"원본 경로",source.toString());
                    String section = "", value;
                    while ((value = in.readLine()) != null) {
                        if (value.startsWith("## ")) section = value.substring(3);
                        if (section.isEmpty() || value.isBlank()) continue;
                        if (value.startsWith("| ")) value = value.replace("<br>", " ")
                                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                                .replace("\\|", "|").replace("\\\\", "\\");
                        add(line,id,2,section,value);
                    }
                }
            }
            for (String missing : existing.keySet()) {
                remove.setString(1,missing); remove.executeUpdate();
            }
        }
    }

    private static void add(PreparedStatement stmt, long id, int kind, String section, String text) throws SQLException {
        stmt.setLong(1,id); stmt.setInt(2,kind); stmt.setString(3,section);
        stmt.setString(4,text); stmt.setString(5,text.toLowerCase(Locale.ROOT)); stmt.executeUpdate();
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
            FROM evidence e JOIN docs d ON d.id=e.doc JOIN json_each(?) q ON q.value=e.word
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
                FROM rows_found r JOIN docs_found f ON r.doc=f.doc JOIN docs d ON d.id=r.doc JOIN lines l ON l.id=r.id)
            SELECT md,source,title,section,text FROM ranked
            ORDER BY score DESC,occurrence,coverage DESC,kind,title,md,id LIMIT ?
            """;
        int kind = switch(scope) {case ALL -> -1; case TITLE -> 0; case CONTENT -> 2;};
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
        String needle = query.toLowerCase(Locale.ROOT);
        boolean indexed = needle.codePointCount(0,needle.length()) >= 3;
        String filter = query.isEmpty() ? "kind=0" : switch (scope) {
            case TITLE -> "kind=0";
            case CONTENT -> "kind=2";
            case ALL -> "(kind<>1 OR instr(lower(d.title),?)=0)";
        };
        String sql = """
                SELECT md,source,title,section,text FROM (
                    SELECT d.md,d.source,d.title,l.section,l.text,l.kind,l.id,
                           row_number() OVER(PARTITION BY l.doc,l.kind ORDER BY l.id) AS occurrence
                    FROM lines l JOIN docs d ON d.id=l.doc WHERE
                """ + filter + (indexed ? " AND l.id IN (SELECT rowid FROM terms WHERE terms MATCH ?)" : "")
                + " AND instr(l.folded,?)>0) ORDER BY kind,occurrence,title,md,id LIMIT ?";
        try (var stmt = db.prepareStatement(sql)) {
            int i = 1;
            if (!query.isEmpty() && scope == Scope.ALL) stmt.setString(i++,needle);
            if (indexed) stmt.setString(i++,"\"" + needle.replace("\"","\"\"") + "\"");
            stmt.setString(i++,needle); stmt.setInt(i,limit);
            return results(stmt);
        }
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
