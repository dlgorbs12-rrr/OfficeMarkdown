import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RankedQualityTest {
    @TempDir Path root;

    void note(String file,String body)throws Exception{
        note(file,file+".docx",file,"## 절\n"+body);
    }
    void note(String file,String source,String title,String body)throws Exception{
        Path md=root.resolve(file+".md");
        Files.writeString(md,"# "+title+"\n\n[원본 파일 열기](<"+root.resolve(source).toUri()+">)\n\n"+body);
        SearchIndex.indexCompleted(root,List.of(md));
    }
    List<Library.Hit> hits(String query)throws Exception{
        return SearchIndex.searchCached(root,query,SearchIndex.Scope.CONTENT,SearchIndex.Mode.RELEVANCE,10);
    }
    void first(String title,String query)throws Exception{
        assertEquals(title,hits(query).getFirst().title());
    }

    @Test void compactHangul()throws Exception{
        note("target","계정 동기화 설정");note("absent","계정 및 동기화");
        assertEquals(List.of("target"),hits("계정동기화").stream().map(Library.Hit::title).toList());
        note("target","다른 내용");
        assertTrue(hits("계정동기화").isEmpty());
    }
    @Test void existingIndexIsBackfilledByNormalWriter()throws Exception{
        note("old","계정 동기화 설정");
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+SearchIndex.database());
            var sql=db.createStatement()){
            sql.execute("DROP TRIGGER rank_delete");
            sql.execute("DROP TABLE rank_headers");
            sql.execute("DROP TABLE rank_sections");
            sql.execute("DROP TABLE rank_joined");
        }
        assertFalse(hits("동기화").isEmpty());
        note("other","관계없는 내용");
        first("old","계정동기화");
    }
    @Test void nearbyLines()throws Exception{
        note("target","백업\n주기\n저장소\n경보\n복원");
        note("absent","백업\n## 다른 절\n주기");
        assertEquals(List.of("target"),hits("백업 주기 저장소 경보 복원").stream().map(Library.Hit::title).toList());
    }
    @Test void columnLabels()throws Exception{
        note("a","VPN 일반 본문");note("target","| 시스템 | 담당자 |\n| --- | --- |\n| VPN | 가상팀 |");
        first("target","VPN 담당자");
        assertTrue(hits("VPN 담당자").getFirst().excerpt().contains("가상팀"));
    }
    @Test void rareTerm()throws Exception{
        for(int i=0;i<6;i++)note("generic"+i,"설정 문서 방법");
        note("target","SAML 설정");
        first("target","SAML 문서 설정 방법");
    }
    @Test void spreadsheetHeaderAndAdjacentTables()throws Exception{
        note("a","VPN 일반 문서");
        note("sheet","| 행 | A | B |\n| --- | --- | --- |\n| 1 | A1 · 시스템 | B1 · 담당자 |\n| 2 | A2 · VPN | B2 · 운영팀 |");
        assertTrue(hits("VPN 담당자").getFirst().excerpt().contains("B2"));
        note("tables","| 구분 | 도시 |\n| --- | --- |\n| 지점 | 서울 |\n| 코드 | 주소 |\n| --- | --- |\n| 시설 | 서부 |");
        assertTrue(hits("시설 주소").getFirst().excerpt().contains("서부"));
    }
    @Test void identifierBoundary()throws Exception{
        note("a","QX-070 만료");note("target","QX-07 만료");
        first("target","QX-07");
    }
    @Test void acronymBoundary()throws Exception{
        note("a","Image 연동");note("target","IM 연동");
        first("target","IM");
    }
    @Test void shortestSpan()throws Exception{
        note("a","백업 관련 검토 기록 담당자 복구 상세 변경 이력 시간");
        note("target","백업 후 복구 소요 시간");
        first("target","백업 복구 시간");
    }
    @Test void sameVersionFamily()throws Exception{
        String shared="## 절\n장애 접수 후 원격 백업 담당자의 확인을 거쳐 순서대로 복원하고 처리 결과를 등록합니다.";
        note("a","안내_v1.2.docx","안내",shared);
        note("b","안내_v1.10.docx","안내",shared);
        note("c","다른문서.docx","타 문서",shared);
        var result=hits("원격 백업");
        assertEquals(2,result.size());
        assertTrue(result.stream().anyMatch(h->h.source().endsWith("안내_v1.10.docx")));
        assertEquals(List.of(root.resolve("안내_v1.2.docx")),result.stream()
                .filter(h->h.source().endsWith("안내_v1.10.docx")).findFirst().orElseThrow().otherSources());
    }
    private List<Library.Hit> slides(String query)throws Exception{
        SlideSearch.prepare(SearchIndex.database());
        String prev=System.getProperty(SlideSearch.ENABLED);
        try{
            System.setProperty(SlideSearch.ENABLED,"true");
            return SearchIndex.searchCached(root,query,SearchIndex.Scope.ALL,SearchIndex.Mode.RELEVANCE,10);
        }finally{
            if(prev==null)System.clearProperty(SlideSearch.ENABLED);else System.setProperty(SlideSearch.ENABLED,prev);
        }
    }
    @Test void deckAndDivider()throws Exception{
        note("deck","deck.pptx","ERP","## 슬라이드 1\n회계\n## 슬라이드 2\n구성도\n업무 설명\n## 슬라이드 3\n휴가 안내\n내부 연락");
        assertEquals(List.of("슬라이드 2"),slides("ERP 회계 구성도 장표").stream().map(Library.Hit::section).toList());
        assertTrue(slides("ERP 장표").isEmpty());
    }
    @Test void joinedHeading()throws Exception{
        note("deck","deck.pptx","발표","## 슬라이드 1\n재고 실사\n## 슬라이드 2\n재고실사\n## 슬라이드 3\n기타\n재고 현황");
        for(String query:List.of("재고실사 장표","재고 실사 장표"))
            assertEquals(java.util.Set.of("슬라이드 1","슬라이드 2"),
                    slides(query).stream().map(Library.Hit::section).collect(java.util.stream.Collectors.toSet()));
    }
}
