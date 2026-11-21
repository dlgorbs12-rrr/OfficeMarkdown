import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfficeIndexMcpTest {
    @TempDir Path root;
    @Test void scopedHandlesFiltersAndCitationWindow() throws Exception {
        Path doc=root.resolve("a.md");
        String note="# IAM 사전조사서\n\n[원본 파일 열기](<"+root.resolve("a.xlsx").toUri()+">)\n\n## 조사\n\n"+"a".repeat(1600)+" 연동항목\n\n## 기타\n\n다른 구역\n";
        Files.writeString(doc,note);
        Files.writeString(root.resolve("b.md"),note.replace("IAM","SSO"));
        SearchIndex.synchronize(root,p->{});
        Files.writeString(root.resolve("config"),OfficeIndexMcp.JSON.toJson(Map.of("root",root.toString(),"focus",doc.toString(),"section","조사")));
        var server=new OfficeIndexMcp(root);
        assertThrows(IllegalArgumentException.class,()->server.call("read_evidence",json("{'document':'1'}")));
        assertEquals(1,((List<?>)server.call("search",json("{'query':'사전조사','field':'title','required':['IAM'],'optional':['SSO']}"))).size());
        assertEquals(0,((List<?>)server.call("search",json("{'query':'사전조사','field':'title','required':['SSO']}"))).size());
        assertEquals(1,((List<?>)server.call("list_sections",json("{'document':'1'}"))).size());
        assertThrows(IllegalArgumentException.class,()->server.call("read_evidence",json("{'document':'1','section':'기타'}")));
        server.call("read_evidence",json("{'document':'1','query':'연동항목'}"));
        assertTrue(Files.readString(root.resolve("E1")).contains("연동항목"));
    }
    @Test void externalReferenceIncludesOriginalSourceAcrossMultipleQuestions() throws Exception {
        var source=root.resolve("example.xlsx");
        Files.writeString(root.resolve("example.md"),"# IAM survey\n\n[원본 파일 열기](<"+source.toUri()+">)\n\n## Questions\n\nApproval owner");
        SearchIndex.synchronize(root,p->{});
        var server=OfficeIndexMcp.external(root);
        for(int i=0;i<32;i++)server.call("search",json("{'field':'title','query':'IAM'}"));
        var result=OfficeIndexMcp.JSON.toJsonTree(server.call("read_evidence",json("{'document':'1','query':'owner'}"))).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(source.toUri().toASCIIString(),result.get("source_uri").getAsString());
        assertEquals("Questions",result.get("section").getAsString());
        assertFalse(Files.exists(root.resolve("E1")));
    }
    @Test void unicodeMatchesAcrossProtocolFieldsAndTitleFilters() throws Exception {
        Files.writeString(root.resolve("demo.md"), "# CAFÉ guide\n\n[원본 파일 열기](<"
                + root.resolve("demo.docx").toUri()+">)\n\n## ÉTAPE\n\nCAFÉ 🔵A");
        SearchIndex.synchronize(root,p->{});
        var server=OfficeIndexMcp.external(root);
        for(String[] sample:new String[][]{{"title","café"},{"body","café"},{"section","étape"},{"body","🔵a"}}){
            var args=new JsonObject();
            args.addProperty("field",sample[0]);args.addProperty("query",sample[1]);
            args.add("required",OfficeIndexMcp.JSON.toJsonTree(List.of("café")));
            assertEquals(1,((List<?>)server.call("search",args)).size(),Arrays.toString(sample));
        }
        var evidence=server.call("read_evidence",json("{'document':'1','query':'🔵a'}"));
        assertTrue(OfficeIndexMcp.JSON.toJson(evidence).contains("CAFÉ 🔵A"));
    }
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
}