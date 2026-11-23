import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {
    @TempDir Path root;
    @Test void everyOutputSharesSearchButKeepsItsOwnDocumentsAndMaintenance() throws Exception {
        Path a = Files.createDirectory(root.resolve("first")), b = Files.createDirectory(root.resolve("second"));
        for (Path folder : java.util.List.of(a,b)) {
            Files.writeString(folder.resolve("same.md"), "# 공통 제목\n\n[원본 파일 열기](<"
                    + folder.resolve("sample.xlsx").toUri()+">)\n\n## 세션\n\n공통 본문 " + folder.getFileName());
            SearchIndex.indexCompleted(folder, java.util.List.of(folder.resolve("same.md")));
        }
        for (Path folder : java.util.List.of(a,b,root.resolve("unused"))) {
            var hits = SearchIndex.searchCached(folder,"공통 본문",SearchIndex.Scope.CONTENT,SearchIndex.Mode.PHRASE,10);
            assertEquals(java.util.Set.of(a.resolve("same.md"),b.resolve("same.md")),
                    hits.stream().map(Library.Hit::md).collect(java.util.stream.Collectors.toSet()));
            assertEquals(2, SearchIndex.agentCandidates(folder,java.util.List.of("본문"),false).size());
            var server = OfficeIndexMcp.external(folder);
            var args = com.google.gson.JsonParser.parseString("{'field':'title','query':'공통'}").getAsJsonObject();
            assertEquals(2, ((java.util.List<?>)server.call("search",args)).size());
        }
        SearchIndex.configureLarge(a,false,java.util.List.of());
        assertTrue(SearchIndex.largeSettings(b).hold());
        Files.delete(a.resolve("same.md"));
        SearchIndex.synchronizeAll(b,p -> {});
        assertEquals(java.util.List.of(b.resolve("same.md")), SearchIndex.libraryCards(a,10).stream().map(Library.Hit::md).toList());
    }

    @Test void importsLegacyDocumentsOnceWithFreshRowIds() throws Exception {
        for (String name : java.util.List.of("a","b")) {
            Path folder = Files.createDirectory(root.resolve(name));
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + folder.resolve(SearchIndex.DATABASE));
                 var sql = db.createStatement()) {
                sql.execute("CREATE TABLE docs(id INTEGER PRIMARY KEY, md, stamp, source, title)");
                sql.execute("CREATE TABLE lines(id INTEGER PRIMARY KEY,doc,kind,section,text,folded)");
                try (var doc = db.prepareStatement("INSERT INTO docs VALUES(1,?,'old',?,?)")) {
                    doc.setString(1,folder.resolve("same.md").toString());
                    doc.setString(2,folder.resolve("source.pdf").toString());
                    doc.setString(3,name);
                    doc.executeUpdate();
                }
                sql.execute("INSERT INTO lines VALUES(1,1,2,'page','Shared phrase','shared phrase')");
            }
            SearchIndex.importPreviousIndex(folder);
            SearchIndex.importPreviousIndex(folder);
        }
        var hits = SearchIndex.searchCached(root,"shared phrase",SearchIndex.Scope.CONTENT,SearchIndex.Mode.PHRASE,10);
        assertEquals(java.util.Set.of("a","b"), hits.stream().map(Library.Hit::title).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,hits.size());
    }
    @Test void combineWordsWithinEachDocumentAndSelectedScope() throws Exception {
        String[] titles = {"설계", "가이드", "검토", "AD 구성", "사용 예"};
        String[] bodies = {"ad 연동\n\n## 슬라이드 2\n\n어댑터 설치", "AD 설정", "어댑터 등록", "어댑터 설치", "AD 어댑터 예제"};
        for (int i=0;i<titles.length;i++) {
            Files.writeString(root.resolve(i+".md"), "# "+titles[i]+"\n\n[원본 파일 열기](<"
                    +root.resolve(i+".pptx").toUri().toASCIIString()+">)\n\n## 슬라이드 1\n\n"+bodies[i]);
        }
        var content = SearchIndex.Scope.CONTENT;
        var and = SearchIndex.Mode.AND;
        assertEquals(java.util.Set.of("설계","사용 예"), titles(" ad  어댑터 AD ",content,and));
        var matches = SearchIndex.search(root,"AD 어댑터",content,and,100);
        assertEquals(2,matches.size());
        var evidence=matches.stream().filter(hit -> hit.title().equals("설계")).findFirst().orElseThrow().excerpt();
        for (String fragment : new String[]{"슬라이드 1","ad 연동","슬라이드 2","어댑터 설치"})
            assertTrue(evidence.contains(fragment),evidence);
        assertEquals(java.util.Set.of("설계","사용 예","AD 구성"), titles("어댑터 AD",SearchIndex.Scope.ALL,and));
        assertEquals(java.util.Set.of(titles), titles("AD 어댑터",content,SearchIndex.Mode.OR));
        assertEquals(java.util.Set.of("사용 예"), titles("AD 어댑터",content,SearchIndex.Mode.PHRASE));
        assertTrue(titles("AD 어댑터",SearchIndex.Scope.TITLE,and).isEmpty());
        assertEquals(java.util.Set.of("AD 구성"),titles("AD 어댑터",SearchIndex.Scope.TITLE,SearchIndex.Mode.OR));
        assertTrue(titles("AD 없는단어",content,and).isEmpty());
    }

    @Test void relevanceCombinesTrigramAndShortCandidatesWithinScope() throws Exception {
        String[] names = {"첫 기록", "둘째 기록", "분류표"};
        String[] bodies = {"연동 𐐨𐐩", "아키텍처", "무관"};
        for (int i = 0; i < names.length; i++) {
            Files.writeString(root.resolve(i + ".md"), "# " + names[i] + "\n\n[원본 파일 열기](<"
                    + root.resolve(i + ".pptx").toUri() + ">)\n\n## 장표\n\n" + bodies[i]);
        }
        var mode = SearchIndex.Mode.RELEVANCE;
        var content = SearchIndex.Scope.CONTENT;
        assertEquals(java.util.Set.of(names[0], names[1]), titles("연동 아키텍처", content, mode));
        assertEquals(java.util.Set.of(names[0]), titles("𐐨𐐩", content, mode));
        assertEquals(java.util.Set.of(names[0]), titles("𐐨𐐩 연동 아키텍처", content, mode));
        assertEquals(java.util.Set.of(names[2]), titles("분류표 아키텍처", SearchIndex.Scope.TITLE, mode));
    }

    @Test void shortWordKeepsTheBestExcerptAcrossCopiesBeforeLimiting() throws Exception {
        Path shared = root.resolve("shared.pptx");
        for (String name : java.util.List.of("first", "second", "third")) {
            String title = name.equals("second") ? "업무" : name;
            Path source = name.equals("third") ? root.resolve("other.pptx") : shared;
            Path note = root.resolve(name + ".md");
            Files.writeString(note, "# " + title + "\n\n[원본 파일 열기](<" + source.toUri() + ">)"
                    + "\n\n## 업무 계획\n\n업무 " + name + "\n업무 같은 점수");
            SearchIndex.indexCompleted(root, java.util.List.of(note));
        }
        var hits = SearchIndex.searchCached(root, "업무", SearchIndex.Scope.ALL, SearchIndex.Mode.RELEVANCE, 1);
        assertEquals(1, hits.size());
        assertEquals(shared, hits.getFirst().source());
        assertEquals(root.resolve("second.md"), hits.getFirst().md());
        assertEquals("## 업무 계획", hits.getFirst().excerpt());
    }

    @Test void broadMixedQueryFindsTheLateFtsOnlyRowAfterSwitchingToPerSourceRanking() throws Exception {
        Path note = root.resolve("broad.md");
        Files.writeString(note, "# reference\n\n[원본 파일 열기](<" + root.resolve("broad.pptx").toUri()
                + ">)\n\n## first\n\nseed");
        SearchIndex.indexCompleted(root, java.util.List.of(note));
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + SearchIndex.database());
             var sql = db.createStatement()) {
            sql.executeUpdate("""
                    WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n<66000)
                    INSERT INTO lines(doc,kind,section,text,folded)
                    SELECT (SELECT id FROM docs),2,'page '||n,'업무','업무' FROM seq
                    """);
            sql.executeUpdate("""
                    INSERT INTO lines(doc,kind,section,text,folded)
                    SELECT id,2,'업무','architecture','architecture' FROM docs
                    """);
        }
        var hits = SearchIndex.searchCached(root, "업무 architecture", SearchIndex.Scope.CONTENT,
                SearchIndex.Mode.RELEVANCE, 1);
        assertEquals(1, hits.size());
        assertEquals("architecture", hits.getFirst().excerpt());
    }

    private java.util.Set<String> titles(String query, SearchIndex.Scope scope, SearchIndex.Mode mode) throws Exception {
        return SearchIndex.search(root,query,scope,mode,100).stream().map(Library.Hit::title)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test void mixedLengthPreviewUsesTheOriginalPhraseAndScope() throws Exception {
        Path literal = root.resolve("literal.md"), partial = root.resolve("partial.md");
        Files.writeString(literal, "# 기록\n\n[원본 파일 열기](<" + root.resolve("literal.docx").toUri()
                + ">)\n\n## 구성\nAD 어댑터 설치법");
        Files.writeString(partial, "# AD 어댑터 문서\n\n[원본 파일 열기](<" + root.resolve("partial.docx").toUri()
                + ">)\n\n## 구성\n어댑터 등록");
        SearchIndex.indexCompleted(root, java.util.List.of(literal, partial));
        var content = SearchIndex.Scope.CONTENT;
        var relevance = SearchIndex.Mode.RELEVANCE;
        var preview = SearchIndex.searchPhrasePreview(root, "ad 어댑터", content, relevance, 10);
        assertEquals(SearchIndex.searchCached(root, "ad 어댑터", content, SearchIndex.Mode.PHRASE, 10), preview);
        assertEquals(java.util.List.of(literal), preview.stream().map(Library.Hit::md).toList());
        var titlePreview = SearchIndex.searchPhrasePreview(root, "ad 어댑터", SearchIndex.Scope.TITLE, relevance, 10);
        assertEquals(java.util.List.of(partial), titlePreview.stream().map(Library.Hit::md).toList());
        assertTrue(SearchIndex.searchPhrasePreview(root, "ad  어댑터", content, relevance, 10).isEmpty());
        assertTrue(SearchIndex.searchPhrasePreview(root, "어댑터 설치법", content, relevance, 10).isEmpty());
        assertTrue(SearchIndex.searchPhrasePreview(root, "ad 어댑터", content, SearchIndex.Mode.OR, 10).isEmpty());
        var ranked = SearchIndex.searchCached(root, "ad 어댑터", content, relevance, 10);
        assertEquals(java.util.List.of(literal, partial), ranked.stream().map(Library.Hit::md).toList());
    }

    @Test void interruptedReaderStopsSqliteDuringQueryExecution() throws Exception {
        Path note = root.resolve("record.md");
        Files.writeString(note, "# record\n\n[원본 파일 열기](<" + root.resolve("record.docx").toUri()
                + ">)\n\n## section\ncontent");
        SearchIndex.indexCompleted(root, java.util.List.of(note));
        try {
            var interrupted = assertThrows(java.io.IOException.class, () -> SearchIndex.readIndex(db -> {
                org.sqlite.Function.create(db, "interrupt_search", new org.sqlite.Function() {
                    @Override protected void xFunc() throws java.sql.SQLException {
                        int row = value_int(0);
                        if (row == 1_000) Thread.currentThread().interrupt();
                        if (row == 50_000) throw new java.sql.SQLException("SQLite did not stop after interruption");
                        result(row);
                    }
                });
                try (var sql = db.createStatement(); var rows = sql.executeQuery("""
                        WITH RECURSIVE sequence(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM sequence WHERE n<50000)
                        SELECT sum(interrupt_search(n)) FROM sequence
                        """)) {
                    return rows.next() ? java.util.List.of(rows.getLong(1)) : java.util.List.of();
                }
            }));
            var cause = assertInstanceOf(org.sqlite.SQLiteException.class, interrupted.getCause());
            assertEquals(org.sqlite.SQLiteErrorCode.SQLITE_INTERRUPT, cause.getResultCode());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void existingNotesEditsAndRemovalAreVisibleAcrossConnections() throws Exception {
        Path md = root.resolve("기록.md"), source = root.resolve("sample.xlsx");
        String note = "# 연말 회의록\n\n[원본 파일 열기](<" + source.toUri().toASCIIString()
                + ">)\n\n원본 경로　" + source + "\n\n## 시트 · 서울\n\n프로젝트 \"ALPHA\" 완료\n";
        Files.writeString(md, note);
        assertEquals("연말 회의록", SearchIndex.search(root,"회의록",SearchIndex.Scope.TITLE,10).getFirst().title());
        var card = SearchIndex.libraryCards(root,10).getFirst();
        assertEquals("시트 · 서울",card.section());
        assertEquals("프로젝트 \"ALPHA\" 완료",card.excerpt());
        assertTrue(SearchIndex.search(root,"회의록",SearchIndex.Scope.CONTENT,10).isEmpty());
        assertEquals(source, SearchIndex.search(root,"\"alpha\"",SearchIndex.Scope.CONTENT,10).getFirst().source());
        assertEquals(md, SearchIndex.search(root,"료",SearchIndex.Scope.CONTENT,10).getFirst().md());
        Files.writeString(md,note.replace("완료","진행 중"));
        assertTrue(SearchIndex.search(root,"완료",SearchIndex.Scope.CONTENT,10).isEmpty());
        assertEquals(1,SearchIndex.search(root,"진행 중",SearchIndex.Scope.CONTENT,10).size());
        Files.delete(md);
        assertTrue(SearchIndex.search(root,"",SearchIndex.Scope.ALL,10).isEmpty());
    }
}
