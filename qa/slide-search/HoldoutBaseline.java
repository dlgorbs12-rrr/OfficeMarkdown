import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Isolated product A engine for the frozen slide comparison. */
class HoldoutBaseline {
    record Source(String alias, String path) {}
    record Page(String alias, int number) {}
    record Request(String query, boolean slides) {}
    record Response(List<Page> pages, double ms, boolean slideRoute) {}
    public static void main(String[] args) throws Exception {
        var gson = new Gson();
        var sourceMap = new HashMap<Path,String>();
        var library = SearchIndex.defaultLibrary();
        if (Files.exists(library) || Files.exists(SearchIndex.database()))
            throw new IOException("Use a fresh experiment directory");
        for (var source : gson.fromJson(Files.readString(Path.of(args[0])), Source[].class)) {
            var path = Path.of(source.path()).toRealPath();
            sourceMap.put(path, source.alias());
            Library.convert(path, library);
        }
        SearchIndex.synchronizeAll(library, ignored -> {});
        boolean productB = args.length > 1 && args[1].equals("--product-b");
        if (productB) SlideSearch.prepare(SearchIndex.database());
        var heading = Pattern.compile("슬라이드 ([0-9]+)");
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("READY");
            for (String line; (line = input.readLine()) != null;) {
                var request = productB ? gson.fromJson(line, Request.class) : new Request(gson.fromJson(line, String.class),false);
                String query = request.query();
                System.setProperty(SlideSearch.ENABLED,Boolean.toString(request.slides()));
                long start = System.nanoTime();
                var hits = SearchIndex.searchCached(library,query,SearchIndex.Scope.ALL,SearchIndex.Mode.RELEVANCE,500);
                var pages = new LinkedHashSet<Page>();
                for (var hit : hits) {
                    var alias = Objects.requireNonNull(sourceMap.get(hit.source()), "Unlisted source");
                    var match = heading.matcher(hit.section());
                    if (match.matches()) pages.add(new Page(alias,Integer.parseInt(match.group(1))));
                }
                var result = new Response(List.copyOf(pages),(System.nanoTime()-start)/1e6,
                        request.slides() && SlideSearch.requested(query));
                System.out.println(gson.toJson(result));
            }
        }
    }
}
