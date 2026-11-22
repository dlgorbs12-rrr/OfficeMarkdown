import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PmReferenceTest {
    @TempDir Path root;

    @Test void surveyReferenceRequiresSurveyAndShowsIdentityEvidence() throws Exception {
        String query="지금 사전조사서 문서를 만들건데 iam/sso야 참고할만한 문서";
        note("IAM SSO 최종검수 확인서","## 본문\n사전조사서 검토 완료\n");
        note("만족도 사전 조사서","## 선호도\n응답 항목\n");
        SearchIndex.synchronize(root,p->{});
        DocumentAgent.Ask generic=p->"[\"IAM\",\"SSO\",\"사전조사서\"]";
        assertTrue(PmReference.surveyReference(query));
        assertTrue(DocumentAgent.locate(root,query,false,generic,p->{}).isEmpty());
        note("통합계정권한관리 통합인증 사전조사서","## 업무시스템 계정권한 및 인증\n업무시스템 | 로그인 방식\n## 스키마\n테이블\n");
        note("산출물 양식집","## 사전조사표\n계정관리 및 인증방식\n");
        SearchIndex.synchronize(root,p->{});
        var found=DocumentAgent.locate(root,query,false,generic,p->{});
        assertEquals(2,found.size());
        assertTrue(found.stream().allMatch(c->c.referenceUse().contains("사전조사서") && c.missing().isEmpty()));
        assertTrue(found.stream().noneMatch(c->c.proof().title().contains("최종검수")));
        assertTrue(DocumentAgent.locate(root,query,false,generic,p->{},new SearchIndex.Focus(root.resolve("만족도 사전 조사서.md"),null)).isEmpty());
        assertFalse(PmReference.surveyReference("사전조사서에 넣을 WBS 예시 찾아줘"));
    }

    @Test void roleSearchUsesBodySectionAndKeepsManualCorrectionsAcrossReindex()throws Exception{
        note("예시 착수보고서","## 표지\n추진\n## 사업 범위\n추진 일정과 역할 분담\n");
        note("예시 가이드","## 절차\n착수보고서 등록 방법\n");
        var plain=root.resolve("예시 가이드.md");
        SearchIndex.synchronize(root,p->{});
        assertEquals("운영 가이드",SearchIndex.documentRole(plain));
        var plan=new PmReference(PmReference.Kind.KICKOFF,List.of("예시"),List.of("범위"));
        var found=SearchIndex.pmReferences(root,plan,null);
        assertEquals(1,found.size());
        assertEquals("사업 범위",found.getFirst().proof().section());
        assertTrue(SearchIndex.pmReferences(root,new PmReference(PmReference.Kind.KICKOFF,List.of("타사"),List.of()),null).isEmpty());
        SearchIndex.setDocumentRole(plain,"착수보고서");
        Files.writeString(plain,"추가 근거\n",java.nio.file.StandardOpenOption.APPEND);
        SearchIndex.synchronize(root,p->{});
        assertEquals(2,SearchIndex.pmReferences(root,plan,null).size());
        SearchIndex.setDocumentRole(plain,"자동 분류");
        assertEquals("운영 가이드",SearchIndex.documentRole(plain));
    }

    @Test void classifiesUnchangedDocumentsInAnOlderIndex()throws Exception{
        note("중립 제목","## 시트 · 사전조사표\n업무시스템과 인증방식\n");
        SearchIndex.synchronize(root,p->{});
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+SearchIndex.database());
            var sql=db.createStatement()){sql.execute("DROP TABLE document_roles");}
        var progress=new java.util.ArrayList<SearchIndex.Progress>();
        SearchIndex.synchronize(root,progress::add);
        assertEquals("사전조사서",SearchIndex.documentRole(root.resolve("중립 제목.md")));
        assertEquals(0,progress.getLast().updated());
    }

    @Test void chatFindsNamedAdapterDesignsWithoutGenericOrSlideResults() throws Exception {
        String query="AD 어댑터 설계 초본을 만들기 위한 자료 탐색";
        note("교육청 AD Adaptor 설계서","## 계정 매핑\n| 컬럼명 | Mapping |\n");
        note("Active Directory 어댑터 설계서 (교육청)","## 조직\n| 필드명 | Mapping |\n");
        note("메일 Adaptor 설계서","## 설정\nAD와 별도 운영\n");
        note("AD 어댑터 설계 교육 가이드","## 교육 일정\n설계서 작성 교육 소개\n");
        note("[회의록] AD 어댑터 설계","## 안건\n설계 검토\n");
        note("AD 운영 가이드","## 계정\n| 필드명 | Mapping |\n");
        SearchIndex.synchronize(root,p->{});
        var reply=McpSearch.chat(null,root,query,"","",null,null,p->{});
        assertEquals(2,reply.sources().size());
        assertTrue(reply.sources().stream().allMatch(c -> c.proof().title().contains("설계서")
                && !c.proof().title().contains("메일") && c.missing().isEmpty()));
        assertTrue(McpSearch.chat(null,root,query,"","",null,new SearchIndex.Focus(root.resolve("메일 Adaptor 설계서.md"),null),p->{}).sources().isEmpty());
        assertNull(AdapterDiscovery.plan("AD 어댑터 설계서와 ERP 설계서 차이를 설명해줘"));
        Path relabelled = root.resolve("AD 어댑터 설계 교육 가이드.md");
        SearchIndex.setDocumentRole(relabelled, "어댑터 설계서");
        assertEquals(1, McpSearch.chat(null,root,query,"","",null,new SearchIndex.Focus(relabelled,null),p->{}).sources().size());
        String followUp = "AD 어댑터 설계서 참고 자료 찾아줘";
        assertEquals(McpSearch.Route.CHAT,McpSearch.route(followUp,"이후 검색은 A 프로젝트 문서 안에서만 해줘",AdapterDiscovery.plan(followUp)));
        assertEquals(McpSearch.Route.ADAPTER,McpSearch.route(followUp,"",AdapterDiscovery.plan(followUp)));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("정책 설계서 참고 자료 찾아줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("시스템 아키텍처 설계서를 찾아줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("슬라이드를 PDF로 저장하는 방법을 알려줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("시스템 아키텍처를 설명한 문서 좀 찾아줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("시스템 구성도가 있는 문서를 한 개 추천해줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("슬라이드 디자인에 어울리는 글꼴을 추천해줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("PPT를 PDF로 변환하려면 어떤 프로그램이 필요해?","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("정책 설계서와 시스템 구성도를 찾아줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("워크숍 단체 사진 한 장 찾아줘","",null));
        assertEquals(McpSearch.Route.CHAT,McpSearch.route("지난달 영수증 한 장 찾아줘","",null));
        assertEquals(McpSearch.Route.SLIDES,McpSearch.route("착수보고서에 쓸 시스템 구성도 잘된 거 찾아줘","",null));
        assertNull(AdapterDiscovery.plan("시스템 구성도 잘된 거 찾아줘"));
    }

    private void note(String title,String content)throws Exception {
        Files.writeString(root.resolve(title+".md"),"# "+title+"\n\n[원본 파일 열기](<"
                +root.resolve(title+".xlsx").toUri()+">)\n\n"+content);
    }

    @Test void findsScheduleToUseInKickoffReportAndKeepsSheetScope() throws Exception {
        note("거래처 WBS","## 표지\n일정\n## 일정표\n| 시작일 | 종료일 | 담당 |\n");
        note("거래처 착수보고서","## 추진일정\n일정\n");
        SearchIndex.synchronize(root,p->{});
        var hits=DocumentAgent.locate(root,"착수보고서에 넣을 일정표 예시",false,p->
                "[{\"source\":\"WBS\",\"ids\":[\"거래처\"],\"topics\":[\"일정\"]},\"일정\"]",p->{});
        var best=hits.getFirst();
        assertEquals("일정표",best.proof().section());
        assertTrue(best.proof().title().contains("WBS"));
        assertTrue(best.referenceUse().contains("시작일"));
        var focused=SearchIndex.pmReferences(root,new PmReference(PmReference.Kind.WBS,List.of(),List.of()),
                new SearchIndex.Focus(best.proof().md(),"표지"));
        assertEquals(1,focused.size());
        assertTrue(focused.getFirst().referenceUse().contains("미확인"));
    }

    @Test void matchesEnglishSpellingAndPrefersRequestedSystemAndSection() throws Exception {
        note("ERP Adaptor 설계서","## 사용자\n| Mapping | 컬럼명 |\n## 조직\n| Mapping | 컬럼명 |\n");
        note("메일 Adapter 설계서","## 조직\n| 컬럼명 |\n");
        SearchIndex.synchronize(root,p->{});
        var hits=DocumentAgent.locate(root,"ERP 어댑터의 조직 매핑 시트",false,p->
                "[{\"source\":\"ADAPTER\",\"ids\":[\"ERP\"],\"topics\":[\"조직\"]}]",p->{});
        assertTrue(hits.getFirst().proof().title().contains("ERP"));
        assertEquals("조직",hits.getFirst().proof().section());
        assertTrue(hits.getFirst().proof().excerpt().contains("컬럼명"));
        assertTrue(hits.stream().anyMatch(c->c.proof().title().contains("메일") && c.missing().contains("ERP")));
    }
}
