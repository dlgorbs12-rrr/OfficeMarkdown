import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Opt-in lexical slide search. The graph and the normal relevance engine are independent. */
final class SlideSearch {
    static final String ENABLED = "officemd.experimentalSlideSearch";
    private static final Set<String> REQUEST = Set.of(
            "장표", "슬라이드", "자료", "그림", "예시", "찾아줘", "찾아주세요", "보여줘", "보여주세요",
            "보여주는", "설명할", "설명하는", "보고", "싶어", "필요해", "참고할", "한눈에", "보이는",
            "정리한", "장으로", "한", "쓸", "때", "있어", "어떻게", "잘된거", "누구", "거쳐가는지");
    private static final Set<String> MODIFIERS = Set.of("추진", "진행", "수행", "전체");
    private static final Set<String> NODE_TYPES = Set.of("서버", "장비", "노드", "시스템");
    private static final Pattern SUFFIX = Pattern.compile(
            "(?<=..)(?:되는지|하는지|기까지|하는|받기까지|부터|까지|으로|에서|에게|들이|로|를|을|이|가|은|는|과|와)$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern PAGE = Pattern.compile("슬라이드 [0-9]+");
    private static final Set<String> REFERENCE_FILLER = Set.of(
            "거", "것", "장", "장이", "잘된", "사용할", "관한", "설명", "해주세요", "필요합니다", "제외", "빼고");

    static boolean requested(String query) {
        return WORD.matcher(query).results().anyMatch(m -> REQUEST.contains(m.group())
                && (m.group().startsWith("찾아") || m.group().startsWith("보여") || m.group().equals("장표")
                    || m.group().equals("슬라이드")));
    }

    static List<String> referenceTerms(String query) {
        var terms = new LinkedHashSet<String>();
        var words = WORD.matcher(query.replace("다른 사람 대신", "대리"));
        while (words.find() && terms.size() < 24) {
            String word = words.group();
            String stem = SUFFIX.matcher(word).replaceFirst("");
            if (REQUEST.contains(word) || REQUEST.contains(stem) || REFERENCE_FILLER.contains(word)
                    || REFERENCE_FILLER.contains(stem) || MODIFIERS.contains(stem)) continue;
            terms.add(stem);
            terms.addAll(switch (stem) {
                case "일정", "일정표", "스케줄", "스케줄표" -> List.of("일정", "스케줄");
                case "흐름", "과정", "절차", "프로세스" -> List.of("흐름", "과정", "절차", "프로세스");
                case "신입사원", "입사자" -> List.of("신입", "입사");
                case "화면", "페이지" -> List.of("화면", "페이지");
                case "구성도", "구조도" -> List.of("구성도", "구조도");
                case "연결", "연동" -> List.of("연결", "연동");
                default -> List.of();
            });
        }
        return terms.stream().limit(24).toList();
    }

    static String match(String query) {
        var groups = new LinkedHashSet<String>();
        String normalized = query.toLowerCase(Locale.ROOT).replace("다른 사람 대신", "대리");
        var stems = new LinkedHashSet<String>();
        String medium = null;
        for (var word : WORD.matcher(normalized).results().map(m -> m.group()).toList()) {
            if (REQUEST.contains(word)) continue;
            String stem = SUFFIX.matcher(word).replaceFirst("");
            if (REQUEST.contains(stem) || MODIFIERS.contains(stem)) continue;
            stems.add(stem);
            if (word.endsWith("로") && !word.equals(stem)) medium = stem;
        }
        // A network diagram may label the topology only in its heading. Keep every
        // additional qualifier mandatory; a generic diagram does not satisfy this clause.
        if (medium != null && !NODE_TYPES.contains(medium) && stems.contains("연결")) {
            var nodes = stems.stream().filter(NODE_TYPES::contains).toList();
            if (nodes.size() == 1 && !medium.equals("연결")) {
                String node = nodes.getFirst();
                stems.removeAll(Set.of(node, medium, "연결"));
                String literal = prefix(node) + " AND " + prefix(medium) + " AND \"연결\"*";
                String heading = "heading:" + prefix(medium) + " AND heading:(\"구성도\"* OR \"연결도\"* OR \"토폴로지\"*)";
                groups.add("((" + literal + ") OR (" + heading + "))");
            }
        }
        for (String stem : stems) {
            // Terms come only from the alphanumeric tokenizer, never from raw FTS syntax.
            String group = switch (stem) {
                case "일정", "일정표", "스케줄", "스케줄표" -> "(\"일정\"* OR \"스케줄\"*)";
                case "흐름", "과정", "절차", "프로세스" -> "(\"흐름\"* OR \"과정\"* OR \"절차\"* OR \"프로세스\"*)";
                case "신입사원", "입사자" -> "(\"신입\"* OR \"입사\"*)";
                case "받기", "받는" -> stems.contains("권한") ? "(" + prefix(stem) + " OR \"부여\"* OR \"획득\"*)" : prefix(stem);
                case "화면", "페이지" -> "(\"화면\"* OR \"페이지\"*)";
                default -> prefix(stem);
            };
            groups.add(group);
        }
        return String.join(" AND ", groups);
    }

    private static String prefix(String word) {
        return "\"" + word + "\"*";
    }

    static boolean installed(Connection db) throws SQLException {
        try (var q = db.createStatement();
             var r = q.executeQuery("SELECT 1 FROM sqlite_master WHERE name='slide_pages' AND type='table'")) {
            return r.next();
        }
    }

    static void prepare(Path database) throws IOException, SQLException {
        if (!Files.isRegularFile(database)) throw new IOException("먼저 보관함 색인을 준비하세요.");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = db.createStatement()) {
            sql.execute("PRAGMA busy_timeout=60000");
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("BEGIN IMMEDIATE");
            try {
                if (!installed(db)) {
                    sql.execute("CREATE TABLE slide_pages(id INTEGER PRIMARY KEY, doc INTEGER NOT NULL REFERENCES docs ON DELETE CASCADE,"
                            + "section TEXT NOT NULL, heading TEXT NOT NULL, body TEXT NOT NULL, UNIQUE(doc,section))");
                    sql.execute("CREATE VIRTUAL TABLE slide_terms USING fts5(heading,body,content='slide_pages',content_rowid='id',prefix='2 3')");
                    sql.execute("CREATE TRIGGER slide_terms_add AFTER INSERT ON slide_pages BEGIN "
                            + "INSERT INTO slide_terms(rowid,heading,body) VALUES(new.id,new.heading,new.body); END");
                    sql.execute("CREATE TRIGGER slide_terms_del AFTER DELETE ON slide_pages BEGIN "
                            + "INSERT INTO slide_terms(slide_terms,rowid,heading,body) VALUES('delete',old.id,old.heading,old.body); END");
                    sql.execute("INSERT INTO slide_terms(slide_terms,rank) VALUES('rank','bm25(6.0,1.0)')");
                    indexSelected(db,"SELECT id FROM docs WHERE lower(source) LIKE '%.pptx'");
                }
                sql.execute("COMMIT");
            } catch (SQLException | RuntimeException failure) {
                sql.execute("ROLLBACK");
                throw failure;
            }
        }
    }

    static void indexSelected(Connection db, String selection) throws SQLException {
        try (var q = db.createStatement(); var docs = q.executeQuery(selection)) {
            while (docs.next()) indexDocument(db, docs.getLong(1));
        }
    }

    static void indexDocument(Connection db, long doc) throws SQLException {
        try (var q = db.prepareStatement("SELECT section,text FROM lines WHERE doc=? AND kind=2 ORDER BY section,id");
             var insert = db.prepareStatement("INSERT INTO slide_pages(doc,section,heading,body) VALUES(?,?,?,?)")) {
            q.setLong(1, doc);
            insert.setLong(1, doc);
            try (var rows = q.executeQuery()) {
                String section = null, heading = "";
                var body = new StringBuilder();
                while (rows.next()) {
                    String current = rows.getString(1);
                    if (!Objects.equals(current, section)) {
                        if (section != null) save(insert, section, heading, body);
                        section = PAGE.matcher(current).matches() ? current : null;
                        heading = "";
                        body.setLength(0);
                    }
                    if (section == null) continue;
                    String value = rows.getString(2);
                    if (value.equals("## " + section) || value.isBlank()) continue;
                    if (body.isEmpty() && !value.startsWith("|")) heading = value;
                    body.append(value).append('\n');
                }
                if (section != null) save(insert, section, heading, body);
            }
        }
    }

    private static void save(PreparedStatement insert, String section, String heading, StringBuilder body) throws SQLException {
        insert.setString(2,section);
        insert.setString(3,heading);
        insert.setString(4,body.toString());
        insert.executeUpdate();
    }

    static List<Library.Hit> search(Connection db, String query, int limit, boolean hasQueue) throws SQLException {
        String terms = match(query);
        if (terms.isEmpty()) return List.of();
        var hits = new ArrayList<Library.Hit>();
        record Position(String source, String section) {}
        var seen = new HashSet<Position>();
        String sql = """
                SELECT d.md,d.source,d.title,p.section,snippet(slide_terms,1,'','',' … ',18)
                FROM slide_terms JOIN slide_pages p ON p.id=slide_terms.rowid JOIN (SELECT d.* FROM docs d %s) d ON d.id=p.doc
                WHERE slide_terms MATCH ? ORDER BY rank
                """.formatted(SearchIndex.visibilityPredicate(hasQueue));
        try (var q = db.prepareStatement(sql)) {
            q.setString(1,terms);
            try (var r = q.executeQuery()) {
                while (hits.size() < limit && r.next()) {
                    if (seen.add(new Position(r.getString(2),r.getString(4))))
                        hits.add(new Library.Hit(Path.of(r.getString(1)),Path.of(r.getString(2)),r.getString(3),r.getString(4),r.getString(5)));
                }
            }
        }
        return hits;
    }
}
