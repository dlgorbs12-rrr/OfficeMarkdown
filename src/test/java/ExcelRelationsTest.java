import java.nio.file.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExcelRelationsTest {
    @TempDir Path dir;
    private Path index(String name,boolean binary,String[][] data,boolean merge) throws Exception {
        Path p=dir.resolve(name+".xlsx");
        try (Workbook book=binary?new HSSFWorkbook():new XSSFWorkbook();var out=Files.newOutputStream(p)) {
            var sheet=book.createSheet("Team's Map");
            for(int i=0;i<data.length;i++){
                var row=sheet.createRow(i);
                for(int j=0;j<data[i].length;j++) if(data[i][j]!=null) row.createCell(j).setCellValue(data[i][j]);
            }
            if(merge) sheet.addMergedRegion(new CellRangeAddress(1,2,0,0));
            book.createSheet("작성사례");
            book.write(out);
        }
        Path note=Library.convert(p,dir.resolve("library"));
        SearchIndex.indexCompleted(note.getParent(),List.of(note));
        return note;
    }
    private static final String S="Team's Map";
    private static final ExcelRelations.Kind K=ExcelRelations.Kind.SCREEN;

    @Test void nativeAndWrappedWorkbookAgreeAndKeepEveryCoordinate() throws Exception {
        String[][] data={{"업무 목록"},{"화면 ID","요구사항\nID","인터페이스 (서비스) ID"},
                {"SC-012, SC-013","REQ-001","IF-001"},{"SC-012","REQ-001",null},{null,"REQ-002",null}};
        for(boolean binary:List.of(false,true)){
            Path p=index("native"+binary,binary,data,false);
            var r=ExcelRelations.lookup(p,S,K,"REQ-001");
            assertTrue(r.complete());
            assertEquals(Set.of("SC-012","SC-013"),r.targets().keySet());
            var edges=r.targets().get("SC-012");
            assertEquals(2,edges.size());
            assertTrue(edges.stream().anyMatch(e->e.fromLocation().equals("'Team''s Map'!B3") &&
                    e.toLocation().equals("'Team''s Map'!A3")));
            assertEquals(Set.of("IF-001"),ExcelRelations.lookup(p,S,ExcelRelations.Kind.INTERFACE,"REQ-001").targets().keySet());
            assertEquals("EXAMPLE_SHEET",ExcelRelations.lookup(p,"작성사례",K,"REQ-001").status());
            assertEquals("NO_HEADER",ExcelRelations.lookup(p,"missing",K,"REQ-001").status());
            assertTrue(ExcelRelations.lookup(p,S,K,"REQ-002").targets().isEmpty());
        }
    }

    @Test void comparisonRequiresCompleteVersionsAndExplicitProject() throws Exception {
        var a=index("a",false,new String[][]{{"Requirement","Screen"},{"REQ-001","SC-001 SC-002"}},false);
        var b=index("b",false,new String[][]{{"Screen","Requirement"},{"SC-002","REQ-001"},{"SC-003","REQ-001"}},false);
        assertEquals("OUTSIDE_PROJECT",ExcelRelations.compare(Set.of(a),a,S,b,S,K,"REQ-001").status());
        assertEquals(Map.of("SC-001","left","SC-002","both","SC-003","right"),
                ExcelRelations.compare(Set.of(a,b),a,S,b,S,K,"REQ-001").matches());
        assertEquals("INCOMPLETE",ExcelRelations.compare(Set.of(a,b),a,S,b,"작성사례",K,"REQ-001").status());
        Files.delete(dir.resolve("b.xlsx"));
        assertEquals("MISSING",ExcelRelations.lookup(b,S,K,"REQ-001").status());
        assertTrue(ExcelRelations.compare(Set.of(a,b),a,S,b,S,K,"REQ-001").matches().isEmpty());
    }

    @Test void duplicateHeadersMergesAndAmbiguousCellsBlockNegativeInference() throws Exception {
        var dup=index("dup",false,new String[][]{{"Requirement","Screen","Screen"},{"REQ-001","SC-001","SC-002"}},false);
        var merged=index("merged",false,new String[][]{{"Requirement","Screen"},{"REQ-001","SC-001"},{null,"SC-002"}},true);
        var many=index("many",false,new String[][]{{"Requirement","Screen"},{"REQ-001 REQ-002","SC-001 SC-002"}},false);
        for(Path p:List.of(dup,merged,many)) assertEquals("PARTIAL",ExcelRelations.lookup(p,S,K,"REQ-001").status());
    }
}
