import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class OfficeSearch {
    public static void main(String[] args) {
        try {
            Path library = SearchIndex.defaultLibrary();
            var scope = SearchIndex.Scope.ALL;
            var mode = SearchIndex.Mode.PHRASE;
            int limit = 50;
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--library" -> library = Path.of(args[++i]);
                    case "--scope" -> scope = SearchIndex.Scope.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--mode" -> mode = SearchIndex.Mode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--limit" -> limit = Integer.parseInt(args[++i]);
                    case "--help" -> {
                        System.out.println("OfficeSearch.exe [--scope all|title|content] [--mode phrase|and|or] [--limit 1..500] [--library DIR] [QUERY]");
                        return;
                    }
                    default -> terms.add(args[i]);
                }
            }
            if (limit < 1 || limit > 500) throw new IllegalArgumentException("--limit 1..500");
            var results = new ArrayList<Map<String,String>>();
            for (var hit : SearchIndex.search(library,String.join(" ",terms),scope,mode,limit))
                results.add(Map.of("title",hit.title(),"section",hit.section(),"excerpt",hit.excerpt(),
                        "markdownPath",hit.md().toString(),"sourcePath",hit.source().toString()));
            System.out.write((new Gson().toJson(results) + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        } catch (Exception e) {
            System.err.println(e);
            System.exit(1);
        }
    }
}
