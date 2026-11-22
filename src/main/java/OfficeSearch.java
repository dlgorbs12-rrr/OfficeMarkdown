import com.google.gson.Gson;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class OfficeSearch {
    public static void main(String[] args) {
        try {
            args = windowsLauncherArguments(args);
            if (args.length > 0 && args[0].equals("--excel-cells")) {
                excelCells(args);
                return;
            }
            Path library = SearchIndex.defaultLibrary();
            boolean refreshOutput = false;
            var scope = SearchIndex.Scope.ALL;
            var mode = SearchIndex.Mode.PHRASE;
            int limit = 50;
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--library" -> {
                        library = Path.of(args[++i]);
                        refreshOutput = true;
                    }
                    case "--scope" -> scope = SearchIndex.Scope.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--mode" -> mode = SearchIndex.Mode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--limit" -> limit = Integer.parseInt(args[++i]);
                    case "--help" -> {
                        System.out.println("OfficeSearch.exe [--scope all|title|content|image] [--mode phrase|and|or|relevance] [--limit 1..500] [--library DIR] [QUERY]");
                        System.out.println("OfficeSearch.exe --excel-cells SCREEN|INTERFACE REQ-ID MARKDOWN SHEET [SECOND_MARKDOWN SECOND_SHEET]");
                        return;
                    }
                    default -> terms.add(args[i]);
                }
            }
            if (limit < 1 || limit > 500) throw new IllegalArgumentException("--limit 1..500");
            var results = new ArrayList<Map<String,String>>();
            if (refreshOutput) SearchIndex.synchronize(library, ignored -> {});
            SearchIndex.importPreviousIndex(SearchIndex.defaultLibrary());
            if (!java.nio.file.Files.exists(SearchIndex.database()))
                SearchIndex.synchronize(library, ignored -> {});
            for (var hit : SearchIndex.searchCached(library,String.join(" ",terms),scope,mode,limit)) {
                var result = new LinkedHashMap<>(Map.of("title",hit.title(),"section",hit.section(),"excerpt",hit.excerpt(),
                        "markdownPath",hit.md().toString(),"sourcePath",hit.source().toString()));
                if (hit.picture() != null) {
                    result.put("imagePath",hit.md().getParent().resolve(hit.picture().relativePath()).toString());
                    result.put("imageLocation",hit.picture().location());
                    if(!hit.additionalOcr().isBlank())result.put("searchOcr",hit.additionalOcr());
                    if(!hit.imageDescription().isBlank())result.put("imageDescription",hit.imageDescription());
                }
                results.add(result);
            }
            System.out.write((new Gson().toJson(results) + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        } catch (Exception e) {
            System.err.println(e);
            System.exit(1);
        }
    }

    private static void excelCells(String[] args) throws Exception {
        if (args.length != 5 && args.length != 7)
            throw new IllegalArgumentException("--excel-cells SCREEN|INTERFACE REQ-ID MARKDOWN SHEET [SECOND_MARKDOWN SECOND_SHEET]");
        var kind = ExcelRelations.Kind.valueOf(args[1].toUpperCase(Locale.ROOT));
        Path left = Path.of(args[3]).toAbsolutePath().normalize();
        Object result;
        if (args.length == 5) result = ExcelRelations.lookup(left,args[4],kind,args[2]);
        else {
            Path right = Path.of(args[5]).toAbsolutePath().normalize();
            result = ExcelRelations.compare(new HashSet<>(List.of(left,right)),left,args[4],right,args[6],kind,args[2]);
        }
        com.google.gson.JsonSerializer<Path> path = (value,type,context) -> new com.google.gson.JsonPrimitive(value.toString());
        var json = new com.google.gson.GsonBuilder().registerTypeHierarchyAdapter(Path.class,path).create();
        System.out.write((json.toJson(result)+"\n").getBytes(StandardCharsets.UTF_8));
    }

    // The Windows Java launcher can replace characters outside the ANSI code page before main().
    private static String[] windowsLauncherArguments(String[] args) {
        if (System.getProperty("jpackage.app-path") == null || !System.getProperty("os.name").startsWith("Windows"))
            return args;
        var kernel = NativeLibrary.getInstance("kernel32");
        Pointer commandLine = kernel.getFunction("GetCommandLineW").invokePointer(new Object[0]);
        var count = new IntByReference();
        Pointer argv = NativeLibrary.getInstance("shell32").getFunction("CommandLineToArgvW")
                .invokePointer(new Object[]{commandLine,count});
        if (argv == null) throw new IllegalStateException("Windows 검색 인수를 읽지 못했습니다.");
        try {
            String[] decoded = new String[count.getValue()-1];
            for (int i=0; i<decoded.length; i++)
                decoded[i] = argv.getPointer((long)(i+1)*Native.POINTER_SIZE).getWideString(0);
            return decoded;
        } finally {
            kernel.getFunction("LocalFree").invokePointer(new Object[]{argv});
        }
    }
}
