import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Streams matching lines in evidence order. SQLite may spill the sort; Java keeps a short window and top k. */
final class RankedText {
    private static final Pattern CODE=Pattern.compile("[A-Za-z0-9]+(?:[-_./][A-Za-z0-9]+)+|[A-Za-z]{2,5}");
    private static final Pattern KOREAN=Pattern.compile("[가-힣]+");
    private record Group(String family,String section,String digest){}
    private record Result(Library.Hit hit,double score,Group group){}
    private record Line(long id,int mask,String text){}
    private static final Comparator<Result> ORDER=Comparator.comparingDouble(Result::score).reversed()
            .thenComparing(r->r.hit.title()).thenComparing(r->r.hit.md()).thenComparing(r->r.hit.section());

    static List<Library.Hit> search(Connection db,String query,SearchIndex.Scope scope,int limit)throws SQLException{
        return search(db,query,scope,limit,"");
    }

    static List<Library.Hit> search(Connection db,String query,SearchIndex.Scope scope,int limit,String sectionFilter)throws SQLException{
        var words=RelevanceSearch.words(query);
        if(words.isEmpty())return List.of();
        boolean fields=RankedFields.installed(db);
        var shortWords=new ArrayList<String>();
        var longWords=new StringJoiner(" OR ");
        var joinedWords=new StringJoiner(" OR ");
        for(String word:words){
            if(word.codePointCount(0,word.length())<3)shortWords.add(word);
            else{
                String quoted="\""+word+"\"";
                longWords.add(quoted);
                if(fields && KOREAN.matcher(word).matches())joinedWords.add(quoted);
            }
        }
        var weights=weights(db,words);
        var scoring=new Scoring(query,words,weights);
        String aux=fields ? """
                LEFT JOIN rank_sections s ON s.doc=l.doc AND s.section=l.section
                LEFT JOIN rank_headers h ON h.first=(SELECT first FROM rank_headers
                    WHERE doc=l.doc AND first<=l.id ORDER BY first DESC LIMIT 1) AND l.id<=h.last
                """ : "";
        var candidates=new StringJoiner(" UNION ");
        if(!shortWords.isEmpty())candidates.add("SELECT id FROM lines WHERE "
                +String.join(" OR ",Collections.nCopies(shortWords.size(),"instr(folded,?)>0")));
        if(longWords.length()>0)candidates.add("SELECT rowid FROM terms WHERE terms MATCH ?");
        if(joinedWords.length()>0)candidates.add("SELECT rowid FROM rank_joined WHERE rank_joined MATCH ?");
        String sql="WITH candidates(id) AS ("+candidates+") SELECT d.md,d.source,d.title,l.section,l.text,l.kind,l.id,l.doc,"
                +(fields?"s.digest,h.header":"NULL,NULL")+" FROM candidates c CROSS JOIN lines l ON l.id=c.id "
                +"JOIN visible_docs d ON d.id=l.doc "+aux
                +switch(scope){case TITLE->" WHERE l.kind=0";case CONTENT->" WHERE l.kind=2";default->"";};
        if(!sectionFilter.isEmpty())sql+=(scope==SearchIndex.Scope.ALL?" WHERE ":" AND ")+"l.section=? AND l.kind=2";
        // Keep converted copies adjacent so the limit applies after choosing the best excerpt.
        var top=new Top(limit);
        try(var q=db.prepareStatement(sql+" ORDER BY d.source,l.section,l.doc,l.id")){
            int bind=1;
            for(String word:shortWords)q.setString(bind++,word);
            if(longWords.length()>0)q.setString(bind++,longWords.toString());
            if(joinedWords.length()>0)q.setString(bind++,joinedWords.toString());
            if(!sectionFilter.isEmpty())q.setString(bind,sectionFilter);
            try(var r=q.executeQuery()){
                String source=null,section=null;
                long doc=-1;
                var recent=new ArrayDeque<Line>();
                Result best=null;
                while(r.next()){
                    String next=r.getString(2),part=r.getString(4);
                    if(!Objects.equals(next,source)||!Objects.equals(part,section)){
                        top.offer(best);best=null;recent.clear();source=next;section=part;
                    }
                    if(doc!=r.getLong(8)){doc=r.getLong(8);recent.clear();}
                    int kind=r.getInt(6);
                    String title=scope==SearchIndex.Scope.CONTENT?"":r.getString(3);
                    String heading=kind==2?section:"";
                    String text=r.getString(5);
                    double score=scoring.score(title,heading,text,r.getString(10),kind,r.getLong(7),recent);
                    if(!Double.isFinite(score) || best!=null && score<=best.score)continue;
                    var hit=new Library.Hit(Path.of(r.getString(1)),Path.of(source),r.getString(3),section,scoring.excerpt,null,"","",List.of(),text);
                    String digest=kind==2?r.getString(9):null;
                    Group key=new Group(digest==null?source:LatestVersionSelection.searchFamily(hit.source()),section,digest);
                    best=new Result(hit,score,key);
                }
                top.offer(best);
            }
        }
        return top.results(db,fields);
    }

    private static double[] weights(Connection db,List<String> words)throws SQLException{
        double[] w=new double[words.size()];Arrays.fill(w,1);
        if(w.length==1)return w;
        int docs;
        try(var q=db.createStatement();var r=q.executeQuery("SELECT count(*) FROM visible_docs")){r.next();docs=r.getInt(1);}
        try(var q=db.prepareStatement("SELECT count(DISTINCT l.doc) FROM terms JOIN lines l ON l.id=terms.rowid JOIN visible_docs d ON d.id=l.doc WHERE terms MATCH ?")){
            for(int i=0;i<w.length;i++){
                String word=words.get(i);
                if(word.codePointCount(0,word.length())<3)continue;
                q.setString(1,"\""+word+"\"");
                try(var r=q.executeQuery()){r.next();int frequency=r.getInt(1);
                    if(frequency>0)w[i]+=Math.min(1.75,Math.log((1d+docs)/(1+frequency)));
                }
            }
        }
        return w;
    }

    private static final class Top {
        final int limit;
        final TreeSet<Result> ranked=new TreeSet<>(ORDER);
        final Map<Group,Result> groups=new HashMap<>();
        Top(int limit){this.limit=limit;}
        void offer(Result candidate){
            if(candidate==null)return;
            Result copy=groups.get(candidate.group);
            if(copy!=null){
                int version=LatestVersionSelection.compareSearchVersions(candidate.hit.source(),copy.hit.source());
                if(candidate.score<copy.score || candidate.score==copy.score && (version<0 ||
                        version==0 && ORDER.compare(candidate,copy)>=0))return;
                ranked.remove(copy);
            }else if(ranked.size()==limit){
                if(ORDER.compare(candidate,ranked.last())>=0)return;
                groups.remove(ranked.pollLast().group);
            }
            ranked.add(candidate);groups.put(candidate.group,candidate);
        }
        List<Library.Hit> results(Connection db,boolean fields)throws SQLException{
            if(!fields)return ranked.stream().map(Result::hit).toList();
            var result=new ArrayList<Library.Hit>();
            try(var q=db.prepareStatement("SELECT DISTINCT d.source FROM rank_sections s JOIN visible_docs d ON d.id=s.doc WHERE s.digest=? AND s.section=? ORDER BY d.source")){
                for(var r:ranked){
                    var h=r.hit;
                    if(r.group.digest!=null){
                        q.setString(1,r.group.digest);q.setString(2,r.group.section);
                        var others=new ArrayList<Path>();
                        try(var rows=q.executeQuery()){
                            while(rows.next()){
                                Path path=Path.of(rows.getString(1));
                                if(!path.equals(h.source()) && LatestVersionSelection.searchFamily(path).equals(r.group.family))others.add(path);
                            }
                        }
                        if(!others.isEmpty())h=new Library.Hit(h.md(),h.source(),h.title(),h.section(),h.excerpt(),null,"","",List.copyOf(others),h.sourceExcerpt());
                    }
                    result.add(h);
                }
            }
            return result;
        }
    }

    private static final class Scoring {
        final List<String> words;
        final String phrase;
        final double[] weights;
        final List<Pattern> exact;
        String excerpt;
        Scoring(String query,List<String> words,double[] weights){
            this.words=words;this.weights=weights;phrase=RelevanceSearch.compact(query);
            exact=CODE.matcher(query).results().filter(m->(m.start()==0 || !ascii(query.charAt(m.start()-1)))
                    && (m.end()==query.length() || !ascii(query.charAt(m.end())))).map(m->m.group()).distinct()
                    .map(code->Pattern.compile("(?<![A-Za-z0-9])"+Pattern.quote(code)+"(?![A-Za-z0-9])",Pattern.CASE_INSENSITIVE)).toList();
        }
        private static boolean ascii(char c){return c<128 && Character.isLetterOrDigit(c);}
        double score(String rawTitle,String rawSection,String raw,String header,int kind,long id,Deque<Line> recent){
            excerpt=raw;
            String title=RelevanceSearch.compact(rawTitle),section=RelevanceSearch.compact(rawSection),body=RelevanceSearch.compact(raw);
            int covered=0,bodyMask=0,titleCount=0;
            double score=0;
            for(int i=0;i<words.size();i++){
                String w=words.get(i);
                boolean t=title.contains(w),s=section.contains(w),b=body.contains(w);
                if(t||s||b){covered|=1<<i;score+=24*weights[i];}
                if(t){titleCount++;score+=3;}
                if(s)score+=7;
                if(b){bodyMask|=1<<i;score+=2;}
            }
            int nearby=0;
            while(!recent.isEmpty() && id-recent.getFirst().id>5)recent.removeFirst();
            boolean prose=kind==2 && !raw.startsWith("|") && !raw.startsWith("#");
            if(prose && bodyMask!=0){
                for(var line:recent)nearby|=line.mask;
                int missing=nearby & ~covered;
                if(missing!=0){
                    var lines=new StringJoiner(" … ");
                    for(var line:recent)if((line.mask & missing)!=0)lines.add(line.text);
                    excerpt=lines.add(raw).toString();
                }
                recent.addLast(new Line(id,bodyMask,raw));
            }else recent.clear();
            String labels=header==null || bodyMask==0?"":RelevanceSearch.compact(header);
            for(int i=0;i<words.size();i++){
                int bit=1<<i;
                if((covered&bit)!=0)continue;
                boolean column=labels.contains(words.get(i));
                if(column || (nearby&bit)!=0){covered|=bit;score+=(column?19:14)*weights[i];}
            }
            if(Integer.bitCount(covered)<RelevanceSearch.minimum(words.size()))return Double.NEGATIVE_INFINITY;
            if(title.contains(phrase))score+=18;
            for(int i=1;i<words.size();i++)if(title.contains(words.get(i-1)+words.get(i)))score+=5;
            if(section.contains(phrase))score+=20;
            if(body.contains(phrase))score+=8;
            if(kind==0 && titleCount==words.size())score+=15;
            if(kind==1)score-=25;
            for(var p:exact)if(p.matcher(raw).find()||p.matcher(rawTitle).find()||p.matcher(rawSection).find())score+=16;
            if(words.size()>1 && Integer.bitCount(bodyMask)==words.size())score+=proximity(body);
            return score;
        }
        private double proximity(String body){
            int[] positions=new int[words.size()];
            int length=0;
            for(int i=0;i<positions.length;i++){positions[i]=body.indexOf(words.get(i));length+=words.get(i).length();}
            int shortest=Integer.MAX_VALUE;
            while(true){
                int first=0,end=0;
                for(int i=0;i<positions.length;i++){
                    if(positions[i]<positions[first])first=i;
                    end=Math.max(end,positions[i]+words.get(i).length());
                }
                shortest=Math.min(shortest,end-positions[first]);
                if(shortest<=length)break;
                positions[first]=body.indexOf(words.get(first),positions[first]+1);
                if(positions[first]<0)break;
            }
            return 8d/(1+Math.max(0,shortest-length));
        }
    }
}
