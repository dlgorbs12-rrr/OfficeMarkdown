import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

/** Runs A and opt-in B on the same isolated product index in alternating order. */
class ProductSlideBenchmark {
    record Doc(String alias, String path) {}
    record Gold(String alias, int number) {}
    record Task(String id, String query, List<Gold> gold) {}
    record Config(List<Doc> docs, List<Task> tasks) {}
    record Result(String id, String mode, int run, double ms, int count, int rank) {}

    public static void main(String[] args) throws Exception {
        if (System.getProperty("officemd.dataDir") == null)
            throw new IllegalArgumentException("Specify an isolated -Dofficemd.dataDir directory");
        var gson = new Gson();
        var config = gson.fromJson(Files.readString(Path.of(args[0])),Config.class);
        int runs = Integer.parseInt(args[2]);
        var library = SearchIndex.defaultLibrary();
        var aliases = new HashMap<Path,String>();
        if (!config.docs.isEmpty() && Files.exists(SearchIndex.database()))
            throw new IllegalArgumentException("Use a fresh index for development documents");
        for (var doc : config.docs) {
            Path path = Path.of(doc.path).toRealPath();
            aliases.put(path,doc.alias);
            Library.convert(path,library);
        }
        if (!config.docs.isEmpty()) SearchIndex.synchronizeAll(library,ignored -> {});
        long size = Files.size(SearchIndex.database());
        long start = System.nanoTime();
        SlideSearch.prepare(SearchIndex.database());
        System.err.printf(Locale.ROOT,"prepare_ms=%.3f growth_bytes=%d%n",(System.nanoTime()-start)/1e6,
                Files.size(SearchIndex.database())-size);
        var results = new ArrayList<Result>();
        var previous = new HashMap<List<String>,List<Library.Hit>>();
        for (int run = 0; run <= runs; run++) {
            int index = 0;
            for (var task : config.tasks) {
                for (char mode : ((run + index) % 2 == 0 ? "AB" : "BA").toCharArray()) {
                    System.setProperty(SlideSearch.ENABLED,String.valueOf(mode == 'B'));
                    long begin = System.nanoTime();
                    var hits = SearchIndex.searchCached(library,task.query,SearchIndex.Scope.ALL,SearchIndex.Mode.RELEVANCE,500);
                    double ms = (System.nanoTime()-begin)/1e6;
                    var key = List.of(task.id,String.valueOf(mode));
                    var prior = previous.putIfAbsent(key,hits);
                    if (prior != null && !prior.equals(hits)) throw new IllegalStateException("Ranking changed: " + key);
                    int count = 0, rank = 0;
                    for (var hit : hits) {
                        if (!hit.source().toString().toLowerCase(Locale.ROOT).endsWith(".pptx")
                                || !hit.section().matches("슬라이드 [0-9]+")) continue;
                        count++;
                        if (rank == 0 && task.gold.contains(new Gold(aliases.get(hit.source()),
                                Integer.parseInt(hit.section().substring("슬라이드 ".length()))))) rank = count;
                    }
                    results.add(new Result(task.id,String.valueOf(mode),run,ms,count,rank));
                }
                index++;
            }
        }
        Files.writeString(Path.of(args[1]),gson.toJson(results), StandardOpenOption.CREATE_NEW);
    }
}
