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
                    : java.util.List.of("--effort",value),e.arguments(p));
            }
        }
        assertThrows(IllegalArgumentException.class,()->DocumentAgent.Effort.MAX.arguments(DocumentAgent.Provider.CODEX));
    }
    @TempDir Path lib;
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
