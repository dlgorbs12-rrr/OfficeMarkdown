import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Exercises production conversion, index and read-only search in a new data directory. */
class WikiBaseline {
    record Question(String id, String family, String task, String query, List<String> docs) {}
    private static final Pattern SCREEN = Pattern.compile("\\bSC-\\d+\\b");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private static JsonObject arguments(String... pairs) {
        var args = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) args.addProperty(pairs[i], pairs[i+1]);
        return args;
    }

    private static Set<String> screens(OfficeIndexMcp mcp, String title, String query) throws Exception {
        var hits = JSON.toJsonTree(mcp.call("search", arguments("field","title","query",title))).getAsJsonArray();
        if (hits.size() != 1 || !title.equals(hits.get(0).getAsJsonObject().get("title").getAsString()))
            throw new IllegalStateException("Expected unique fixture: " + title);
        var args = arguments("document",hits.get(0).getAsJsonObject().get("document").getAsString(),
                "section","시트 · Mapping","query",query);
        var ids = new TreeSet<String>();
        for (int offset = 0; ; offset += 8) {
            args.addProperty("offset", offset);
            var rows = JSON.toJsonTree(mcp.call("read_evidence",args)).getAsJsonArray();
            for (var row : rows) SCREEN.matcher(row.getAsJsonObject().get("text").getAsString())
                    .results().forEach(match -> ids.add(match.group()));
            if (rows.size() < 8) return ids;
        }
    }

    private static Collection<String> run(Question q, Path library, OfficeIndexMcp mcp) throws Exception {
        if (q.task.equals("row")) return screens(mcp,q.docs.get(0),q.query);
        if (q.task.equals("agreement")) {
            var left = screens(mcp,q.docs.get(0),q.query);
            var right = screens(mcp,q.docs.get(1),q.query);
            var combined = new TreeSet<>(left);
            combined.addAll(right);
            var result = new ArrayList<String>();
            for (String id : combined) {
                String side = left.contains(id) ? (right.contains(id) ? "both" : "left") : "right";
                result.add(side + "/" + id);
            }
            return result;
        }
        var scope = q.task.equals("image") ? SearchIndex.Scope.IMAGE : SearchIndex.Scope.ALL;
        var results = new LinkedHashSet<String>();
        for (var hit : SearchIndex.searchCached(library,q.query,scope,SearchIndex.Mode.RELEVANCE,500)) {
            if (!hit.section().isBlank()) results.add(hit.source().getFileName() + "@" + hit.section());
        }
        return results;
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectory(output);
        System.setProperty("officemd.dataDir",output.toString());
        Path library = SearchIndex.defaultLibrary();
        var notes = new HashMap<String,Path>();
        boolean nativeCells = args.length == 4 && args[3].equals("--excel-cells");
        long start = System.nanoTime();
        try (var files = Files.list(source)) {
            for (var file : files.sorted().toList()) notes.put(file.getFileName().toString(),Library.convert(file,library));
        }
        long converted = System.nanoTime();
        SearchIndex.synchronizeAll(library, ignored -> {});
        long indexed = System.nanoTime();
        long bytes;
        try (var files = Files.list(SearchIndex.database().getParent())) {
            bytes = 0;
            for (var f : files.toList()) bytes += Files.size(f);
        }
        var mcp = OfficeIndexMcp.external(library);
        var questions = JSON.fromJson(Files.readString(Path.of(args[2])),Question[].class);
        var predictions = new LinkedHashMap<String,Collection<String>>();
        var times = new ArrayList<Double>();
        for (var q : questions) {
            long before = System.nanoTime();
            if (nativeCells && q.task.equals("row")) {
                var result=ExcelRelations.lookup(notes.get(q.docs.getFirst()),"Mapping",ExcelRelations.Kind.SCREEN,q.query);
                if (!result.complete()) throw new IllegalStateException(result.status());
                predictions.put(q.id,result.targets().keySet());
            } else if (nativeCells && q.task.equals("agreement")) {
                Path left=notes.get(q.docs.getFirst()), right=notes.get(q.docs.getLast());
                var result=ExcelRelations.compare(Set.of(left,right),left,"Mapping",right,"Mapping",ExcelRelations.Kind.SCREEN,q.query);
                if (!result.status().equals("COMPLETE")) throw new IllegalStateException(result.status());
                predictions.put(q.id,result.matches().entrySet().stream().map(e->e.getValue()+"/"+e.getKey()).toList());
            } else predictions.put(q.id,run(q,library,mcp));
            times.add((System.nanoTime()-before)/1e6);
        }
        Files.writeString(output.resolve("predictions.json"),JSON.toJson(predictions));
        Files.writeString(output.resolve("cost.json"),JSON.toJson(Map.of(
                "convert_ms",(converted-start)/1e6,"index_ms",(indexed-converted)/1e6,
                "index_bytes",bytes,"query_ms",times)));
    }
}
