import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

class DocumentAgentTest {
    @Test void selectedDepthIsTranslatedForEachCli(){
        for(var p:DocumentAgent.Provider.values()){
            assertTrue(DocumentAgent.Effort.DEFAULT.arguments(p).isEmpty());
            for(var e:DocumentAgent.Effort.choices(p)){
                if(e==DocumentAgent.Effort.DEFAULT)continue;
                String value=e.name().toLowerCase(java.util.Locale.ROOT);
                assertEquals(p==DocumentAgent.Provider.CODEX
                    ? java.util.List.of("-c","model_reasoning_effort="+value)
                    : java.util.List.of(p==DocumentAgent.Provider.COPILOT?"--reasoning-effort":"--effort",value),e.arguments(p));
            }
        }
        assertThrows(IllegalArgumentException.class,()->DocumentAgent.Effort.MAX.arguments(DocumentAgent.Provider.CODEX));
    }
    @TempDir Path lib;
    @Test void followUpKeepsDocumentAndSectionUntilScopeIsCleared() throws Exception {
        Path first=lib.resolve("first.md");
        for(String name:java.util.List.of("first","second"))
            Files.writeString(lib.resolve(name+".md"),"# "+name+"\n\n[원본 파일 열기](<"+lib.resolve(name+".pptx").toUri()+">)\n\n"
                    +"## 슬라이드 1\n로그인 화면\n## 슬라이드 2\n로그인 화면 및 결재 설정\n");
        SearchIndex.synchronize(lib, ignored->{});
        var scope=new SearchIndex.Focus(first,"슬라이드 1");
        var words=java.util.List.of("로그인");
        var hits=SearchIndex.agentCandidates(lib,words,false,scope);
        assertEquals(1,hits.size());assertEquals(first,hits.getFirst().md());
        assertEquals("슬라이드 1",hits.getFirst().section());
        assertEquals(2,SearchIndex.agentCandidates(lib,words,false,new SearchIndex.Focus(first,null)).size());
        assertEquals(4,SearchIndex.agentCandidates(lib,words,false,null).size());
        assertTrue(SearchIndex.agentCandidates(lib,java.util.List.of("결재 설정"),false,scope).isEmpty());
        DocumentAgent.answer(lib,"여기 요약",prompt->{
            if(prompt.contains("핵심 명사"))return "[\"요약\"]";
            assertTrue(prompt.contains("로그인 화면"));assertFalse(prompt.contains("결재 설정"));return "[]";
        },ignored->{},scope);
    }
    @Test void locationSearchRanksCluesInTheSameSectionAndPreservesPhrases() throws Exception {
        Path md=lib.resolve("proposal.md");
        Files.writeString(md,"# 제안서\n\n[원본 파일 열기](<"+lib.resolve("proposal.pptx").toUri()+">)\n\n"
                +"## 슬라이드 23\n목표 시스템 구성도\n## 슬라이드 24\n목표 시스템 구성도\nSSO 인증 연동\n"
                +"## 슬라이드 25\n시스템 점검 및 구성도\n");
        SearchIndex.synchronize(lib, ignored -> {});
        var hits=DocumentAgent.locate(lib,"구성도 찾아줘",false,
                prompt -> "[\"목표 시스템 구성도\",\"SSO 인증 연동\"]",ignored -> {});
        assertEquals(java.util.List.of("슬라이드 24","슬라이드 23"),
                hits.stream().map(c -> c.proof().section()).toList());
        assertEquals(md,hits.getFirst().proof().md());
        assertEquals(java.util.Set.of("목표 시스템 구성도","sso 인증 연동"),
                hits.getFirst().evidence().stream().map(SearchIndex.Evidence::term).collect(java.util.stream.Collectors.toSet()));
        assertTrue(hits.getFirst().missing().isEmpty());
        assertEquals(java.util.List.of("SSO 인증 연동"),hits.get(1).missing());
        assertTrue(AgentDialog.relevanceHtml(hits.getFirst(),false).contains("<b>SSO 인증 연동</b>"));
    }
    @Test void sheetIdentifierCanMatchInsideACompoundHeading() throws Exception {
        Path md=lib.resolve("manual.md");
        Files.writeString(md,"# TIDE 인터페이스 안내\n\n[원본 파일 열기](<"+lib.resolve("a.xlsx").toUri()
                +">)\n\n## 시트 · 안내\n공통 준비\n## 시트 · 연동_RTD\n측정 대상");
        SearchIndex.synchronize(lib,p->{});
        var hits=DocumentAgent.locate(lib,"TIDE의 RTD 시트를 찾아줘",false,
                p->"[\"TIDE\",\"RTD 시트\"]",s->{});
        assertFalse(hits.isEmpty());
        assertEquals("시트 · 연동_RTD",hits.getFirst().proof().section());
    }
    @Test void findByTitleWhenNoBodySectionRepeatsIt() throws Exception {
        Path md=lib.resolve("title.md");
        Files.writeString(md,"# 오로라 체크인\n\n[원본 파일 열기](<"+lib.resolve("a.pptx").toUri()
                +">)\n\n## 오전\n공개 장소는 본관입니다.");
        SearchIndex.synchronize(lib,p->{});
        var hits=DocumentAgent.locate(lib,"이 문서를 찾아줘",false,p->"[\"오로라 체크인\"]",s->{});
        assertEquals(1,hits.size());
        assertEquals(md,hits.getFirst().proof().md());
        assertEquals("문서명",hits.getFirst().evidence().getFirst().field());
    }
    @Test void citedAnswerUsesActualMarkdownAndRejectsInventedEvidence() throws Exception{
        Path md=lib.resolve("일정.md");
        Files.writeString(md,"# 출시 계획\n\n[원본 파일 열기](<"+lib.resolve("plan.xlsx").toUri()+">)\n\n## 시트 · 일정\n\n공개 날짜는 12월 14일입니다.\n");
        var calls=new AtomicInteger();
        var answers=DocumentAgent.answer(lib,"언제 발표하나요?",prompt->{
            if(calls.getAndIncrement()==0)return "[\"출시\",\"공개\",\"날짜\"]";
            assertTrue(prompt.contains("12월 14일"));
            return "[{\"answer\":\"12월 14일입니다.\",\"id\":1,\"quote\":\"공개 날짜는 12월 14일입니다.\"},"
                    +"{\"answer\":\"틀린 인용\",\"id\":1,\"quote\":\"1월 1일\"},{\"answer\":\"가짜 출처\",\"id\":2,\"quote\":\"공개\"}]";
        },s->{});
        assertEquals(1,answers.size());assertEquals(md,answers.getFirst().proof().md());
        assertEquals("시트 · 일정",answers.getFirst().proof().section());
        assertTrue(DocumentAgent.answer(lib,"다른 문서",p->"[\"존재하지않는단어\"]",s->{}).isEmpty());
    }
}
