import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/** Explicit requirement-to-target relationships, scoped to one version and sheet. */
final class ExcelRelations {
    static final String VERSION = "xlsx-cell-v1";
    private static final String HEADER = "xlsx.column-pair";
    private static final String PARTIAL = "xlsx.partial-column-pair";
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)*[-_][0-9]+");
    enum Kind { SCREEN, INTERFACE }
    record Lookup(String status, EvidenceIndex.Version version, Map<String,List<EvidenceIndex.Edge>> targets) {
        boolean complete() { return status.equals("COMPLETE"); }
    }
    record Comparison(String status, Lookup left, Lookup right, Map<String,String> matches) {}

    private static final Pattern EXAMPLE = Pattern.compile("사례|예시|샘플|가이드|example|sample|template",Pattern.CASE_INSENSITIVE);

    static boolean example(String sheet) {
        return EXAMPLE.matcher(sheet).find();
    }

    static Lookup lookup(Path note, String sheet, Kind kind, String requirement) throws IOException {
        try { return readCells(note,sheet,kind,requirement); }
        catch (java.io.InterruptedIOException e) { throw e; }
        catch (IOException e) { return new Lookup("READ_ERROR",null,Map.of()); }
    }

    private static Lookup readCells(Path note, String sheet, Kind kind, String requirement) throws IOException {
        var current = EvidenceIndex.read(note,VERSION);
        if (current.version()==null || !current.version().source().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            return new Lookup(current.version()==null ? current.state().name() : "UNSUPPORTED",current.version(),Map.of());
        if (example(sheet)) return new Lookup("EXAMPLE_SHEET",current.version(),Map.of());
        if (current.state()==EvidenceIndex.State.STALE && EvidenceIndex.read(note,current.version().extractor()).state()==EvidenceIndex.State.STALE)
            return new Lookup("STALE",current.version(),Map.of());
        if (current.state()==EvidenceIndex.State.PENDING || (current.state()==EvidenceIndex.State.STALE
                && !VERSION.equals(current.version().extractor()))) {
            EvidenceIndex.extract(note,VERSION,XlsxStreaming::relations);
            current = EvidenceIndex.read(note,VERSION);
        }
        if (current.state()!=EvidenceIndex.State.READY)
            return new Lookup(current.state().name(),current.version(),Map.of());
        String status="NO_HEADER";
        var targets=new TreeMap<String,List<EvidenceIndex.Edge>>();
        String prefix=sheetPrefix(sheet);
        for (var edge : current.edges()) {
            if (edge.from().equals(sheet) && edge.to().equals(kind.name())) {
                if (edge.kind().equals(HEADER)) status="COMPLETE";
                if (edge.kind().equals(PARTIAL)) status="PARTIAL";
            }
            if (edge.kind().equals(kind.name()) && edge.from().equalsIgnoreCase(requirement)
                    && edge.fromLocation().startsWith(prefix))
                targets.computeIfAbsent(edge.to(),ignored->new ArrayList<>()).add(edge);
        }
        return new Lookup(status,current.version(),status.equals("COMPLETE") ? targets : Map.of());
    }

    static Comparison compare(Set<Path> project, Path leftNote, String leftSheet, Path rightNote, String rightSheet,
                              Kind kind, String requirement) throws IOException {
        if (!project.contains(leftNote) || !project.contains(rightNote))
            return new Comparison("OUTSIDE_PROJECT",null,null,Map.of());
        var left=lookup(leftNote,leftSheet,kind,requirement);
        var right=lookup(rightNote,rightSheet,kind,requirement);
        if (!left.complete() || !right.complete()) return new Comparison("INCOMPLETE",left,right,Map.of());
        for (var lookup : List.of(left,right)) {
            if (EvidenceIndex.read(lookup.version()).state()!=EvidenceIndex.State.READY)
                return new Comparison("STALE",left,right,Map.of());
        }
        var targets=new TreeMap<String,String>();
        for (String id:left.targets.keySet()) targets.put(id,right.targets.containsKey(id)?"both":"left");
        for (String id:right.targets.keySet()) targets.putIfAbsent(id,"right");
        return new Comparison("COMPLETE",left,right,targets);
    }

    static String sheetPrefix(String sheet) { return "'"+sheet.replace("'","''")+"'!"; }
    private static String header(String value) {
        return switch (value.replaceAll("\\s+","").toLowerCase(Locale.ROOT)) {
            case "요구사항id","requirement","requirementid" -> "REQ";
            case "화면id","screen","screenid" -> "SCREEN";
            case "인터페이스id","인터페이스(서비스)id","interface","interfaceid" -> "INTERFACE";
            default -> "";
        };
    }

    static final class SheetScan {
        private final String sheet;
        private final List<EvidenceIndex.Edge> edges;
        private final Map<Integer,String> row=new HashMap<>();
        private final Map<String,Integer> columns=new HashMap<>();
        private final Set<String> partial=new HashSet<>();
        private final List<CellRangeAddress> merges=new ArrayList<>();
        private int active=-1, headerRow=-1;

        SheetScan(String sheet,List<EvidenceIndex.Edge> edges) { this.sheet=sheet; this.edges=edges; }
        void cell(int r,int c,String value) {
            if (active!=r) { flush(); row.clear(); active=r; }
            row.put(c,value);
        }
        void merge(CellRangeAddress range) { merges.add(range); }
        private String address(int col,int r) { return sheetPrefix(sheet)+CellReference.convertNumToColString(col)+(r+1); }

        private void flush() {
            if (row.isEmpty()) return;
            if (headerRow<0 || row.values().stream().anyMatch(v->header(v).equals("REQ"))) {
                var found=new HashMap<String,Integer>();
                var duplicates=new HashSet<String>();
                row.forEach((col,v)->{
                    String h=header(v);
                    if (!h.isEmpty() && found.putIfAbsent(h,col)!=null) duplicates.add(h);
                });
                if (!found.containsKey("REQ") || found.size()<2) { if (headerRow>=0) partial.add("REQ"); return; }
                if (headerRow>=0 && !columns.equals(found)) partial.add("REQ");
                if (headerRow<0) { columns.putAll(found); headerRow=active; }
                partial.addAll(duplicates);
                return;
            }
            int reqCol=columns.get("REQ");
            String raw=row.getOrDefault(reqCol,"").strip();
            if (raw.isEmpty()) return;
            var requests=ids(raw);
            for (Kind kind:Kind.values()) {
                Integer col=columns.get(kind.name());
                if (col==null) continue;
                String target=row.getOrDefault(col,"").strip();
                if (target.isEmpty()) continue;
                var targets=ids(target);
                if (requests.isEmpty() || targets.isEmpty() || (requests.size()>1 && targets.size()>1)) {
                    partial.add(kind.name());
                    continue;
                }
                for (String req:requests) for (String id:targets)
                    edges.add(new EvidenceIndex.Edge(kind.name(),req,id,address(reqCol,active),address(col,active)));
            }
        }

        private static Set<String> ids(String text) {
            var ids=new LinkedHashSet<String>();
            for (String part:text.split("[\\s,;/]+")) {
                if (!ID.matcher(part).matches()) return Set.of();
                ids.add(part.toUpperCase(Locale.ROOT));
            }
            return ids;
        }

        void finish() {
            flush();
            if (headerRow<0) return;
            for (Kind kind:Kind.values()) {
                Integer col=columns.get(kind.name());
                if (col==null) continue;
                int req=columns.get("REQ");
                boolean merged=merges.stream().anyMatch(m->m.getLastRow()>=headerRow &&
                        (inColumns(m,req) || inColumns(m,col)));
                boolean incomplete=merged || partial.contains("REQ") || partial.contains(kind.name());
                edges.add(new EvidenceIndex.Edge(incomplete?PARTIAL:HEADER,sheet,kind.name(),
                        address(req,headerRow),address(col,headerRow)));
            }
        }
        private static boolean inColumns(CellRangeAddress range,int c) {
            return range.getFirstColumn()<=c && c<=range.getLastColumn();
        }
    }

    static void scanWorkbook(Workbook book,List<EvidenceIndex.Edge> edges) {
        var display=new DataFormatter(Locale.ROOT);
        for (Sheet sheet:book) {
            if (example(sheet.getSheetName())) continue;
            var scan=new SheetScan(sheet.getSheetName(),edges);
            for (Row row:sheet) for (Cell cell:row)
                scan.cell(row.getRowNum(),cell.getColumnIndex(),switch(cell.getCellType()) {
                    case FORMULA, ERROR -> "#UNREADABLE";
                    default -> display.formatCellValue(cell);
                });
            sheet.getMergedRegions().forEach(scan::merge);
            scan.finish();
        }
    }
}
