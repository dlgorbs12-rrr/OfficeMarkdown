import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

final class MarkdownReader {
    static final String PICTURE_ANCHOR = "office-markdown-picture";
    record Page(String section, int part, String source) {
        public String toString() { return section+(part>1?"  ("+part+")":""); }
    }

    static List<Page> read(Path file) throws IOException {
        List<Page> pages=new ArrayList<>();
        scan(file,page->{ pages.add(page); return false; });
        return pages;
    }

    static Page previewPage(Path file, Library.Hit hit) throws IOException {
        class Selection {
            Page first, match;
            boolean accept(Page page) {
                if (!page.section().equals(hit.section())) return false;
                if (first==null) first=page;
                String readable=page.source().replace("<br>"," ").replace("&lt;","<").replace("&gt;",">")
                        .replace("&amp;","&").replace("\\|","|").replace("\\\\","\\");
                if (!readable.contains(hit.excerpt())) return false;
                match=page;
                return true;
            }
        }
        var selection=new Selection();
        scan(file,selection::accept);
        return selection.match!=null ? selection.match : selection.first;
    }

    private static void scan(Path file, java.util.function.Predicate<Page> stop) throws IOException {
        String section="문서 처음", tableHeader="", divider="", previous="";
        StringBuilder chunk=new StringBuilder();
        int part=1, lines=0;
        try (var in=Files.newBufferedReader(file)) {
            String line;
            int fileLine = 0;
            while ((line=in.readLine())!=null) {
                fileLine++;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if ((fileLine == 6 || fileLine == 7) && PictureAssets.generation(line) != null) continue;
                if (line.startsWith("## ")) {
                    if (!chunk.isEmpty() && stop.test(new Page(section,part,chunk.toString()))) return;
                    section=line.substring(3); chunk.setLength(0); lines=0; part=1;
                    tableHeader=""; divider="";
                } else if ((lines>=100 || chunk.length()>32000) && (line.isBlank() || line.startsWith("| "))) {
                    if (stop.test(new Page(section,part++,chunk.toString()))) return;
                    chunk.setLength(0); lines=0;
                    if (line.startsWith("| ") && !divider.isEmpty()) chunk.append(tableHeader).append('\n').append(divider).append('\n');
                }
                if (line.startsWith("|") && line.contains("---") && line.matches("[|: \\t-]+")) {
                    tableHeader=previous; divider=line;
                } else if (!line.startsWith("|")) { tableHeader=""; divider=""; }
                chunk.append(line).append('\n'); previous=line; lines++;
            }
        }
        stop.test(new Page(section,part,chunk.toString()));
    }

    static int locate(List<Page> pages,Library.Hit hit) {
        if (hit.picture() != null) {
            for (int i = 0; i < pages.size(); i++) {
                if (containsPicture(pages.get(i), hit.picture())) return i;
            }
        }
        String section=hit.section(), sample=hit.excerpt();
        if (section.equals("모두 포함")) {
            String[] evidence=sample.split("\\R",3);
            if (evidence.length>=2 && evidence[0].contains("  ·  ")) {
                section=evidence[0].split("  ·  ",2)[1];
                sample=evidence[1].replace("…","");
            }
        }
        int first=-1;
        for(int i=0;i<pages.size();i++) {
            Page page=pages.get(i);
            if (!page.section().equals(section)) continue;
            if(first<0) first=i;
            String readable=page.source().replace("<br>"," ").replace("&lt;","<").replace("&gt;",">")
                    .replace("&amp;","&").replace("\\|","|").replace("\\\\","\\");
            if(readable.contains(sample)) return i;
        }
        return Math.max(0,first);
    }

    static String html(Page page) {
        return html(page, null);
    }

    static boolean containsPicture(Page page, PictureAssets.Reference picture) {
        return page.source().lines().anyMatch(PictureAssets.markdownLine(picture)::equals);
    }

    static String html(Page page, PictureAssets.Reference picture) {
        var extensions=List.of(TablesExtension.create());
        var parser=Parser.builder().extensions(extensions).build();
        var renderer=HtmlRenderer.builder().extensions(extensions).softbreak("<br>").escapeHtml(true).build();
        String source = page.source();
        String marker = null;
        if (picture != null && containsPicture(page,picture)) {
            marker = "OfficePictureAnchor" + UUID.randomUUID().toString().replace("-", "");
            source = ("\n" + source).replace("\n" + PictureAssets.markdownLine(picture) + "\n", "\n" + marker + "\n").substring(1);
        }
        String rendered=renderer.render(parser.parse(source)).replace("&lt;br&gt;","<br>");
        var allowed=new Safelist().addTags("p","h1","h2","h3","h4","h5","h6","strong","em","code","pre",
                "blockquote","ul","ol","li","table","thead","tbody","tr","td","th","br","hr");
        String safe = Jsoup.clean(rendered,allowed);
        if (marker != null) safe = safe.replace(marker, "<a name='" + PICTURE_ANCHOR + "'></a>");
        return "<html><head><style>body {font-family:'Malgun Gothic';font-size:12pt;color:#302b40;margin:14px}"
                +"h1,h2,h3 {color:#57419d;margin-top:10px;margin-bottom:8px} h1 {font-size:19pt} h2 {font-size:16pt}"
                +"p {margin-top:5px;margin-bottom:9px} table {border-spacing:0} td,th {border:1px solid #dcd8e7;padding:5px}"
                +"th {background-color:#eeeaf7;color:#3d3158} pre,code {background-color:#f4f2f7}"
                +"</style></head><body>"+safe.replace("<table>","<table width='100%' cellpadding='6' cellspacing='1' bgcolor='#ddd9e7'>").replace("<td>","<td bgcolor='#ffffff'>").replace("<th>","<th bgcolor='#eeeaf7'>")+"</body></html>";
    }
}
