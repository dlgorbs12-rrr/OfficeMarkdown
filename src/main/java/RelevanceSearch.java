import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Ranked retrieval. The literal and Boolean search modes keep their own match semantics. */
final class RelevanceSearch {
    private static final Pattern SEPARATORS=Pattern.compile("[\\p{Z}\\s_·./()\\[\\]-]+");
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    static List<String> words(String query){
        return WORD.matcher(query.toLowerCase(Locale.ROOT)).results().map(r->r.group())
                .distinct().limit(16).toList();
    }
    private static String compact(String text){
        return SEPARATORS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private record Ranked(Library.Hit hit,double score){}
    private record Position(Path source,String section,String picture){}

    private static void keep(Map<Position,Ranked> best,Library.Hit hit,double score){
        var key=new Position(hit.source(),hit.section(),hit.picture()==null?"":hit.picture().number());
        var old=best.get(key);
        if(old==null || score>old.score())best.put(key,new Ranked(hit,score));
    }
    private static List<Library.Hit> top(Map<Position,Ranked> best,int limit){
        return best.values().stream().sorted(RANK_ORDER)
                .limit(limit).map(Ranked::hit).toList();
    }
    private static int minimum(int count){return Math.max(1,(count+1)/2);}

    private static double textScore(List<String> words, String phrase, String title, String section, String body, int field) {
        int covered=0,titleCount=0,sectionCount=0,bodyCount=0;
        for(var word:words){
            boolean t=title.contains(word),s=section.contains(word),b=body.contains(word);
            if(t||s||b)covered++;
            if(t)titleCount++;
            if(s)sectionCount++;
            if(b)bodyCount++;
        }
        if(covered<minimum(words.size()))return Double.NEGATIVE_INFINITY;
        double score=covered*24+titleCount*3+sectionCount*7+bodyCount*2;
        if(title.contains(phrase))score+=18;
        for(int i=1;i<words.size();i++)
            if(title.contains(words.get(i-1)+words.get(i)))score+=5;
        if(section.contains(phrase))score+=20;
        if(body.contains(phrase))score+=8;
        if(field==0 && titleCount==words.size())score+=15;
        if(field==1)score-=25;
        return score;
    }

    private record TextDocument(long id, Path note, Path source, String title) {}
    private static final int MAX_BUFFERED_POSITIONS = 65536;
    private static final Comparator<Ranked> RANK_ORDER = Comparator.comparingDouble(Ranked::score).reversed()
            .thenComparing(r -> r.hit.title()).thenComparing(r -> r.hit.md()).thenComparing(r -> r.hit.section());

    // A frequent short word can match millions of sections. Finalize one source at a time,
    // including all its converted notes, before merging that source into the global top results.
    private static List<Library.Hit> bySource(Connection db, List<String> words, String phrase, SearchIndex.Scope scope, int limit,
                                              List<String> shortWords, String longMatch)
            throws SQLException {
        var sources = new LinkedHashMap<Path,List<TextDocument>>();
        try (var q = db.createStatement(); var r = q.executeQuery("SELECT id,md,source,title FROM visible_docs")) {
            while (r.next()) {
                Path source = Path.of(r.getString(3));
                sources.computeIfAbsent(source, ignored -> new ArrayList<>())
                        .add(new TextDocument(r.getLong(1), Path.of(r.getString(2)), source, r.getString(4)));
            }
        }
        var top = new PriorityQueue<Ranked>(RANK_ORDER.reversed());
        boolean canonicalWord = words.size() == 1 && words.getFirst().equals(phrase);
        var headings = new HashMap<String,String>();
        String filter = switch (scope) {
            case TITLE -> " AND kind=0";
            case CONTENT -> " AND kind=2";
            default -> "";
        };
        var predicates = new ArrayList<>(Collections.nCopies(shortWords.size(), "instr(folded,?)>0"));
        if (!longMatch.isEmpty()) {
            // Keep the FTS posting list on this search connection; each source reuses it.
            try (var create = db.createStatement()) {
                create.execute("CREATE TEMP TABLE ranked_postings(id INTEGER PRIMARY KEY)");
            }
            try (var fill = db.prepareStatement("INSERT INTO ranked_postings SELECT rowid FROM terms WHERE terms MATCH ?")) {
                fill.setString(1, longMatch);
                fill.executeUpdate();
            }
            predicates.add("EXISTS (SELECT 1 FROM ranked_postings p WHERE p.id=l.id)");
        }
        // SQLite can spill the per-source sort to disk. Only the current section and
        // the final result heap are kept in Java, even for one very large source.
        String tail = filter + " AND (" + String.join(" OR ", predicates) + ") ORDER BY section COLLATE BINARY,id";
        String select = "SELECT doc,kind,section,text FROM lines l WHERE ";
        try (var single = db.prepareStatement(select + "doc=?" + tail);
             var copies = db.prepareStatement(select + "doc IN (SELECT value FROM json_each(?))" + tail)) {
            for (var q : List.of(single, copies)) {
                int bind = 2;
                for (String word : shortWords) q.setString(bind++, word);
            }
            for (var notes : sources.values()) {
                var q = notes.size() == 1 ? single : copies;
                var first = notes.getFirst();
                var docs = new HashMap<Long,TextDocument>();
                if (notes.size() == 1) q.setLong(1, first.id);
                else {
                    var ids = new StringJoiner(",", "[", "]");
                    for (var doc : notes) {
                        ids.add(Long.toString(doc.id));
                        docs.put(doc.id, doc);
                    }
                    q.setString(1, ids.toString());
                }
                String previous = null;
                Ranked winner = null;
                TextDocument doc = first;
                String title = scope == SearchIndex.Scope.CONTENT ? "" : compact(doc.title);
                try (var r = q.executeQuery()) {
                    while (r.next()) {
                        String section = r.getString(3);
                        if (!Objects.equals(previous, section)) {
                            offer(top, winner, limit);
                            previous = section;
                            winner = null;
                        }
                        long id = r.getLong(1);
                        if (id != doc.id) {
                            doc = docs.get(id);
                            title = scope == SearchIndex.Scope.CONTENT ? "" : compact(doc.title);
                        }
                        int kind = r.getInt(2);
                        if (headings.size() >= 4096) headings.clear();
                        String heading = kind == 2 ? headings.computeIfAbsent(section, RelevanceSearch::compact) : "";
                        if (canonicalWord && top.size() == limit
                                && textScore(words, phrase, title, heading, phrase, kind) < top.element().score) continue;
                        String text = r.getString(4);
                        double score = textScore(words, phrase, title, heading, compact(text), kind);
                        if (score == Double.NEGATIVE_INFINITY || (winner != null && score <= winner.score)) continue;
                        var hit = new Library.Hit(doc.note, doc.source, doc.title, section, text);
                        winner = new Ranked(hit, score);
                    }
                }
                offer(top, winner, limit);
            }
        }
        return top.stream().sorted(RANK_ORDER).map(Ranked::hit).toList();
    }

    private static void offer(PriorityQueue<Ranked> top, Ranked winner, int limit) {
        if (winner == null) return;
        if (top.size() == limit) {
            if (RANK_ORDER.compare(winner, top.element()) >= 0) return;
            top.remove();
        }
        top.add(winner);
    }

    static List<Library.Hit> text(Connection db,String query,SearchIndex.Scope scope,int limit)throws SQLException{
        var words=words(query);
        if(words.isEmpty())return List.of();
        String phrase=compact(query);
        var best=new HashMap<Position,Ranked>();
        // FTS5 trigram queries require three Unicode code points. Scan the short words together.
        var shortWords = new ArrayList<String>();
        var phrases = new StringJoiner(" OR ");
        for (String word : words) {
            if (word.codePointCount(0, word.length()) < 3) shortWords.add(word);
            else phrases.add("\"" + word + "\"");
        }
        if (words.size() == 1 && !shortWords.isEmpty())
            return bySource(db, words, phrase, scope, limit, shortWords, phrases.toString());
        String shortMatch = String.join(" OR ", Collections.nCopies(shortWords.size(), "instr(l.folded, ?) > 0"));
        String fields = "SELECT d.md,d.source,d.title,l.section,l.text,l.kind ";
        String docs = " JOIN visible_docs d ON d.id=l.doc ";
        int kind=scope==SearchIndex.Scope.TITLE?0:scope==SearchIndex.Scope.CONTENT?2:-1;
        String fieldFilter = kind == -1 ? "" : " AND l.kind=?";
        String sql;
        if (phrases.length() == 0) {
            sql = fields + "FROM lines l" + docs + "WHERE (" + shortMatch + ")" + fieldFilter;
        } else {
            String candidates = "SELECT rowid FROM terms WHERE terms MATCH ?";
            if (!shortWords.isEmpty()) {
                candidates += " UNION SELECT l.id FROM lines l WHERE " + shortMatch;
            }
            sql = "WITH candidates(id) AS (" + candidates + ") " + fields
                    + "FROM candidates c CROSS JOIN lines l ON l.id=c.id" + docs + "WHERE 1=1" + fieldFilter;
        }
        try(var q=db.prepareStatement(sql + " ORDER BY l.id")){
            int param = 1;
            if (phrases.length() > 0) q.setString(param++, phrases.toString());
            for (String word : shortWords) q.setString(param++, word);
            if (kind != -1) q.setInt(param, kind);
            try(var r=q.executeQuery()){
                while(r.next()){
                    int field=r.getInt(6);
                    String title=scope==SearchIndex.Scope.CONTENT?"":compact(r.getString(3));
                    String section=field==2?compact(r.getString(4)):"",body=compact(r.getString(5));
                    double score = textScore(words, phrase, title, section, body, field);
                    if (score == Double.NEGATIVE_INFINITY) continue;
                    keep(best,new Library.Hit(Path.of(r.getString(1)),Path.of(r.getString(2)),r.getString(3),
                            r.getString(4),r.getString(5)),score);
                    if (best.size() >= Math.max(MAX_BUFFERED_POSITIONS, 4L * limit)) break;
                }
            }
        }
        if (best.size() >= Math.max(MAX_BUFFERED_POSITIONS, 4L * limit)) {
            best.clear();
            return bySource(db, words, phrase, scope, limit, shortWords, phrases.toString());
        }
        return top(best,limit);
    }

    // Indexer keeps the source alt text immediately before the generated figure caption.
    // Bind it to that exact occurrence. A single slide can contain dozens of unrelated pictures.
    private record Caption(int doc,String section,String caption){}
    private static Map<Caption,String> captions(Connection db)throws SQLException{
        var result=new HashMap<Caption,String>();
        String sql="""
                SELECT l.doc,n.section,n.text,l.text FROM terms JOIN lines l ON l.id=terms.rowid
                JOIN lines n ON n.doc=l.doc AND n.id=l.id+1 AND n.section=l.section
                WHERE terms MATCH '"그림 ·"' AND l.text LIKE '그림 · %' AND n.text GLOB '그림 [0-9]* · *'
                """;
        try(var stmt=db.createStatement();var r=stmt.executeQuery(sql)){
            while(r.next())result.put(new Caption(r.getInt(1),r.getString(2),r.getString(3)),r.getString(4).substring(5));
        }
        return result;
    }
    private static String withoutFinalConsonants(String text){
        var out=new StringBuilder(text.length());
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            out.append(c>='가' && c<='힣' ? (char)(c-(c-'가')%28) : c);
        }
        return out.toString();
    }
    private record Picture(Library.Hit hit,String ocr,String label,String digest){}
    static List<Library.Hit> pictures(Connection db,String query,int limit,SearchIndex.Focus focus)throws SQLException{
        var words=words(query);
        if(words.isEmpty())return List.of();
        var labels=captions(db);
        var candidates=new ArrayList<Picture>();
        var frequency=new int[words.size()];
        String sql="""
                SELECT d.id,d.md,d.source,d.title,p.section,p.text,p.path,p.location,p.search_ocr,p.digest FROM searchable_pictures p
                JOIN visible_docs d ON d.id=p.doc
                """+(focus==null?"":" WHERE d.md=?"+(focus.section()==null?"":" AND p.section=?"))+" ORDER BY p.id";
        int total=0;
        try(var q=db.prepareStatement(sql)){
            if(focus!=null){q.setString(1,focus.md().toString());if(focus.section()!=null)q.setString(2,focus.section());}
            try(var r=q.executeQuery()){
                while(r.next()){
                    total++;
                    var ref=new PictureAssets.Reference(r.getString(7),r.getString(8));
                    String caption="그림 "+ref.number()+" · "+ref.location();
                    String label=labels.getOrDefault(new Caption(r.getInt(1),r.getString(5),caption),"");
                    String additional=Objects.toString(r.getString(9),"");
                    String ocr=compact(r.getString(6).isBlank()?additional:r.getString(6)),foldedLabel=compact(label);
                    int count=0;
                    for(int i=0;i<words.size();i++){
                        if(ocr.contains(words.get(i)) || foldedLabel.contains(words.get(i))){count++;frequency[i]++;}
                    }
                    if(count>=minimum(words.size())){
                        var hit=new Library.Hit(Path.of(r.getString(2)),Path.of(r.getString(3)),r.getString(4),
                                r.getString(5),r.getString(6),ref,label,additional);
                        candidates.add(new Picture(hit,ocr,foldedLabel,r.getString(10)));
                    }
                }
            }
        }
        String phrase=compact(query);
        var best=new HashMap<Position,Ranked>();
        var distinct=new LinkedHashMap<String,Ranked>();
        for(var p:candidates){
            double score=0;
            String consonants=withoutFinalConsonants(p.ocr);
            for(int i=0;i<words.size();i++){
                String word=words.get(i);
                double rarity=1+Math.log((total+1d)/(1+frequency[i]));
                if(p.ocr.contains(word))score+=rarity;
                else if(p.label.contains(word))score+=rarity*.85;
                else if(word.length()>=3 && word.chars().allMatch(c->c>='가' && c<='힣')
                        && consonants.contains(withoutFinalConsonants(word)))score+=rarity*.70;
            }
            int start=p.ocr.indexOf(phrase);
            if(start>=0)score*=1.5+0.5/(1+start/60d);
            else if(p.label.contains(phrase))score*=1.5;
            for(int i=1;i<words.size();i++){
                String adjacent=words.get(i-1)+words.get(i);
                int position=p.ocr.indexOf(adjacent);
                if(position>=0)score+=2.5/(1+position/100d);
            }
            // For the same evidence, a short dialog or heading is more specific than a full screen of OCR.
            score/=1+0.012*Math.sqrt(p.ocr.length()+p.label.length());
            if(p.digest==null)keep(best,p.hit,score);
            else{
                var old=distinct.get(p.digest);
                if(old==null || score>old.score)distinct.put(p.digest,new Ranked(p.hit,score));
            }
        }
        for(var r:distinct.values())keep(best,r.hit,r.score);
        return top(best,limit);
    }
}
