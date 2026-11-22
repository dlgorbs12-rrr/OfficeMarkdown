import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Ranked retrieval. The literal and Boolean search modes keep their own match semantics. */
final class RelevanceSearch {
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}]+");
    static List<String> words(String query){
        return WORD.matcher(query.toLowerCase(Locale.ROOT)).results().map(r->r.group())
                .distinct().limit(16).toList();
    }
    static String compact(String text){
        String lower = text.toLowerCase(Locale.ROOT);
        int length = lower.length();
        StringBuilder joined = null;
        int start = 0;
        for (int i = 0; i < length; i++) {
            char c = lower.charAt(i);
            if (!separator(c)) continue;
            if (joined == null) joined = new StringBuilder(length);
            joined.append(lower, start, i);
            start = i + 1;
        }
        return joined == null ? lower : joined.append(lower, start, length).toString();
    }

    private static boolean separator(char c) {
        return switch (c) {
            case ' ', '\t', '\n', '\u000B', '\f', '\r', '_', '·', '.', '/', '(', ')', '[', ']', '-' -> true;
            default -> c >= '\u00A0' && Character.isSpaceChar(c);
        };
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
    static int minimum(int count){return Math.max(1,(count+1)/2);}
    private static final Comparator<Ranked> RANK_ORDER = Comparator.comparingDouble(Ranked::score).reversed()
            .thenComparing(r -> r.hit.title()).thenComparing(r -> r.hit.md()).thenComparing(r -> r.hit.section());

    static List<Library.Hit> text(Connection db,String query,SearchIndex.Scope scope,int limit)throws SQLException{
        return RankedText.search(db,query,scope,limit);
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
