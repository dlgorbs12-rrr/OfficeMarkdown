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
