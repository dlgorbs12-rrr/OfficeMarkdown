import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;

/** Measures the storage API, independently of format-specific extractors and search accuracy. */
class EvidenceCost {
    private static long indexBytes() throws Exception {
        try (var files = Files.list(SearchIndex.database().getParent())) {
            long total = 0;
            for (var file : files.toList()) total += Files.size(file);
            return total;
        }
    }

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[1]).toAbsolutePath();
        Files.createDirectory(data);
        System.setProperty("officemd.dataDir",data.toString());
        var notes = new ArrayList<Path>();
        try (var sources = Files.list(Path.of(args[0]))) {
            for (var source : sources.sorted().toList()) notes.add(Library.convert(source,SearchIndex.defaultLibrary()));
        }
        SearchIndex.indexCompleted(SearchIndex.defaultLibrary(),notes);
        long before = indexBytes();
        var edges = new ArrayList<EvidenceIndex.Edge>();
        for (int i=0; i<50; i++)
            edges.add(new EvidenceIndex.Edge("benchmark","A"+i,"B"+i,"part/left/"+i,"part/right/"+i));
        var queryMs = new ArrayList<Double>();
        long start = System.nanoTime();
        for (Path note : notes) EvidenceIndex.extract(note,"cost-v1",snapshot->edges);
        double publishMs = (System.nanoTime()-start)/1e6;
        long added = indexBytes()-before;
        for (int i=0; i<10; i++) {
            for (var note : notes) {
                long t = System.nanoTime();
                var result = EvidenceIndex.read(note,"cost-v1");
                queryMs.add((System.nanoTime()-t)/1e6);
                if (result.state()!=EvidenceIndex.State.READY || !Set.copyOf(result.edges()).equals(Set.copyOf(edges)))
                    throw new AssertionError("Stored edges differ");
            }
        }
        queryMs.sort(null);
        System.out.println(new Gson().toJson(Map.of("documents",notes.size(),"edges",notes.size()*edges.size(),
                "additional_bytes",added,"publish_ms",publishMs,"reads",queryMs.size(),
                "read_p95_ms",queryMs.get((int)Math.ceil(.95*queryMs.size())-1))));
    }
}
