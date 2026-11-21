import java.nio.file.*;
import java.util.*;

/** Replays one PM question through the selected route, recording the first three results. */
class McpComparison {
    public static void main(String[] args) throws Exception {
        if(args.length<3 || args.length>4 || !Set.of("legacy","mcp").contains(args[1]))
            throw new IllegalArgumentException("LIBRARY legacy|mcp QUESTION [OUTPUT]");
        Path root=Path.of(args[0]);
        String question=args[2];
        var trace=new ArrayList<String>();
        long start=System.nanoTime();
        List<DocumentAgent.Claim> hits;
        if(args[1].equals("mcp")){
            hits=McpSearch.ask(root,question,"",DocumentAgent.Effort.LOW,null,trace::add);
        }else{
            hits=DocumentAgent.locate(root,question,false,prompt->{
                trace.add("CLI");
                return DocumentAgent.invoke(DocumentAgent.Provider.CODEX,DocumentAgent.Effort.LOW,prompt);
            },status->{});
        }
        var lines=new ArrayList<String>();
        lines.add(args[1]+" "+((System.nanoTime()-start)/1000000)+" ms "+trace+" total "+hits.size());
        for(var claim:hits.stream().limit(3).toList()){
            var hit=claim.proof();
            boolean exact=claim.evidence().stream().anyMatch(e->!e.quote().isBlank() && hit.excerpt().contains(e.quote()));
            lines.add(hit.title()+" | "+hit.section()+" | exactEvidence="+exact);
        }
        if(args.length==4)Files.write(Path.of(args[3]),lines);
        lines.forEach(System.out::println);
    }
}