import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryTest {
    @TempDir Path root;
    @Test void vectorImagesArePaintedAndUnknownFormatsAreReported() throws Exception {
        byte[] svg="<svg xmlns='http://www.w3.org/2000/svg' width='50' height='30'><rect width='50' height='30' fill='red'/></svg>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var image=OfficePicture.decode(svg,OfficePicture.type(svg,"svg"));
        assertEquals(0xff0000,image.getRGB(15,15)&0xffffff);
        try(var ocr=new ImageOcr(ImageOcr.Mode.WINDOWS,ImageOcr.scripts())) {
            var output=new java.io.StringWriter();
            ocr.write(new byte[]{1,2,3},"unknown","시트 A",output);
            assertTrue(ocr.warnings.stream().anyMatch(v -> v.contains("UNKNOWN") && v.contains("시트 A")));
        }
    }
    @Test void readTextFromPixelsInSlideAndImageOnlySheet() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var image=new java.awt.image.BufferedImage(720,125,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0,0,720,125);
        g.setColor(java.awt.Color.BLACK); g.setFont(new java.awt.Font("Malgun Gothic",java.awt.Font.BOLD,52));
        g.drawString("문서 검색 7392",25,82); g.dispose();
        var bytes=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"PNG",bytes);
        Path ppt=root.resolve("발표.pptx"), xls=root.resolve("자료.xlsx"), lib=root.resolve("색인");
        try (var show=new XMLSlideShow(); var out=Files.newOutputStream(ppt)) {
            show.createSlide();
            show.createSlide().createPicture(show.addPicture(bytes.toByteArray(),org.apache.poi.sl.usermodel.PictureData.PictureType.PNG));
            show.write(out);
        }
        try(var book=new XSSFWorkbook(); var out=Files.newOutputStream(xls)) {
            var sheet=book.createSheet("현황");
            var anchor=book.getCreationHelper().createClientAnchor(); anchor.setRow1(2); anchor.setCol1(3);
            sheet.createDrawingPatriarch().createPicture(anchor,book.addPicture(bytes.toByteArray(),Workbook.PICTURE_TYPE_PNG));
            book.write(out);
        }
        for (Path p : java.util.List.of(ppt,xls)) {
            String md;
            try (var engine=new ImageOcr(ImageOcr.Mode.WINDOWS,ImageOcr.scripts())) {
                md=Files.readString(Library.convert(p,lib,engine));
            }
            assertTrue(md.contains("7392"),md);
            if (p.equals(xls)) assertTrue(md.contains("D3"),md);
        }
        var hits=Library.search(lib,"7392",10);
        assertEquals(java.util.Set.of("슬라이드 2","시트 · 현황"),
                hits.stream().map(Library.Hit::section).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void worksheetsKeepRowContextAndSparseAddresses() throws Exception {
        Path file = root.resolve("재고.xlsx"), lib = root.resolve("색인");
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(file)) {
            var row = book.createSheet("창고").createRow(2);
            row.createCell(2).setCellValue("강남");
            row.createCell(4).setCellValue("수량|금액\n합계");
            var sparse = book.createSheet("집계").createRow(5);
            sparse.createCell(0).setCellValue("재고");
            sparse.createCell(99).setCellFormula("1+2");
            book.write(out);
        }
        String text = Files.readString(Library.convert(file, lib));
        assertTrue(text.contains("| 행 | C | D | E |"));
        assertTrue(text.contains("| 셀 주소 | 내용 |"));
        assertTrue(text.contains("| CV6 |"));
        assertTrue(text.contains("(=1+2)"));
        var hit = Library.search(lib, "수량|금액", 10).getFirst();
        assertEquals("시트 · 창고", hit.section());
        assertTrue(hit.excerpt().contains("C3 · 강남"));
        assertTrue(hit.excerpt().contains("E3 · 수량|금액 합계"));
    }
    @Test void searchableSlideChartPictureAndTable() throws Exception {
        var file = root.resolve("자료.pptx");
        var lib = root.resolve("색인");
        try (var show = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var slide = show.createSlide();
            var table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("항목");
            table.getCell(1, 1).setText("비용|수익");
            var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", bytes);
            var pic = slide.createPicture(show.addPicture(bytes.toByteArray(), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG));
            var props = ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture)pic.getXmlObject()).getNvPicPr().getCNvPr();
            props.setDescr("물류 창고 배치도");
            var chart = show.createChart();
            slide.addChart(chart);
            var chartType = chart.getCTChart().getPlotArea().addNewBarChart();
            var series = chartType.addNewSer();
            series.addNewTx().setV("서울 매출");
            var category = series.addNewCat().addNewStrLit().addNewPt();
            category.setIdx(0); category.setV("상반기");
            var value = series.addNewVal().addNewNumLit().addNewPt();
            value.setIdx(0); value.setV("1200");
            show.write(out);
        }
        var md = Files.readString(Library.convert(file, lib));
        assertTrue(md.contains("| --- | --- |"));
        for (var keyword : new String[]{"비용|수익", "물류 창고", "서울 매출", "상반기 · 1200"})
            assertEquals("슬라이드 1", Library.search(lib, keyword, 10).getFirst().section(), keyword);
    }
    @Test void convertAndFindAllFourFormats() throws Exception {
        Path lib = root.resolve("결과");
        for (String ext : new String[]{"ppt", "pptx", "xls", "xlsx"}) {
            Path file = root.resolve("예제 #1." + ext);
            try (var out = Files.newOutputStream(file)) {
                switch (ext) {
                    case "ppt" -> { try (var show = new HSLFSlideShow()) {
                        show.createSlide().createTextBox().setText("기준 문구"); show.write(out);
                    }}
                    case "pptx" -> { try (var show = new XMLSlideShow()) {
                        show.createSlide().createTextBox().setText("기준 문구"); show.write(out);
                    }}
                    default -> { try (Workbook book = ext.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook()) {
                        book.createSheet("계획").createRow(2).createCell(3).setCellValue("기준 문구");
                        book.write(out);
                    }}
                }
            }
            byte[] before = Files.readAllBytes(file);
            Path note = Library.convert(file, lib);
            assertEquals(note, Library.convert(file, lib));
            assertArrayEquals(before, Files.readAllBytes(file));
            var hit = Library.search(lib, "기준 문구", 500).stream()
                    .filter(h -> h.source().equals(file)).findFirst().orElseThrow();
            assertEquals(ext.startsWith("ppt") ? "슬라이드 1" : "시트 · 계획", hit.section());
            if (ext.startsWith("xls")) assertTrue(hit.excerpt().contains("D3"));
            String previous = Files.readString(note);
            Files.writeString(file, "broken");
            assertThrows(Exception.class, () -> Library.convert(file, lib));
            assertEquals(previous, Files.readString(note));
        }
        assertEquals(4, Library.search(lib, "", 500).size());
    }
}
