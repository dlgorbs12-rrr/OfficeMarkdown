import com.google.gson.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Search plan for PMs looking for source material for a deliverable. */
record PmReference(Kind kind,List<String> identifiers,List<String> topics) {
    enum Kind {
        WBS("WBS", List.of("시작일","종료일","담당","작업일정","task","duration")),
        ADAPTER("어댑터 설계서", List.of("인터페이스명","송신시스템","필드명","컬럼명","mapping","매핑","데이터타입")),
        SURVEY("사전조사서", List.of("업무시스템","계정관리","권한관리","인증방식","로그인","연계방식","담당자")),
        DESIGN("설계서",List.of("설계","구성","필드","인터페이스","정책")),
        KICKOFF("착수보고서",List.of("추진","일정","범위","역할")),
        PLAN("계획서",List.of("목표","일정","담당","범위")),
        TEST("시험서·결과서",List.of("기대결과","실제결과","시나리오","검증")),
        GUIDE("운영 가이드",List.of("절차","설정","관리","방법")),
        PROPOSAL("제안서",List.of("제안","구성","요구사항","수행"));
        @Override public String toString(){return label;}
        final String label;
        final List<String> markers;
        Kind(String label,List<String> markers){this.label=label;this.markers=markers;}
    }

    private static final Pattern SIGNAL=Pattern.compile("wbs|일정표|작업.?(일정|분해)|공정표|어댑터|어댑타|어뎁터|ad[ao]pt[eo]r",Pattern.CASE_INSENSITIVE);
    private static final Pattern SURVEY_NAME=Pattern.compile("사전[\\s_-]*조사(서|표|지)?");
    static Kind classify(String text){
        String s=fold(text);
        if(s.contains("wbs")||s.contains("일정표"))return Kind.WBS;
        if(s.contains("설계") && s.matches(".*(어댑터|어댑타|어뎁터|adapter|adaptor).*"))return Kind.ADAPTER;
        if(SURVEY_NAME.matcher(s).find())return Kind.SURVEY;
        if(s.contains("착수보고"))return Kind.KICKOFF;
        if(s.contains("시험")||s.contains("테스트")||s.contains("검증결과"))return Kind.TEST;
        if(s.contains("설계")||s.contains("정의서"))return Kind.DESIGN;
        if(s.contains("계획서"))return Kind.PLAN;
        if(s.contains("가이드")||s.contains("매뉴얼")||s.contains("운영지침"))return Kind.GUIDE;
        if(s.contains("제안서"))return Kind.PROPOSAL;
        return null;
    }
    static boolean applicable(String question){return SIGNAL.matcher(question).find()||classify(question)!=null;}

    static boolean surveyReference(String question){
        return SURVEY_NAME.matcher(question).find() && !SIGNAL.matcher(question).find()
                && !Pattern.compile("착수보고서|완료보고서|정책\\s*설계서").matcher(question).find()
                && Pattern.compile("참고|참조|양식|샘플|예시|찾아|찾고|찾을|찾는").matcher(question).find()
                && !Pattern.compile("그림|스크린샷|이미지").matcher(question).find();
    }

    static PmReference surveyPlan(String question,PmReference plan){
        var ids=new LinkedHashSet<String>(plan==null?List.of():plan.identifiers);
        var topics=new LinkedHashSet<String>(plan==null?List.of():plan.topics);
        String q=fold(question);
        if(q.contains("iam")||q.contains("통합계정"))topics.add("IAM");
        if(q.contains("sso")||q.contains("통합인증"))topics.add("SSO");
        ids.removeIf(id->id.equalsIgnoreCase("IAM")||id.equalsIgnoreCase("SSO")||id.equalsIgnoreCase("IAM/SSO"));
        return new PmReference(Kind.SURVEY,List.copyOf(ids),List.copyOf(topics));
    }

    static String prompt(String question){
        return """
                NETS 프로젝트 PM이 찾는 참고자료를 해석하세요. 작성하려는 산출물과 찾을 원본을 구분하세요.
                착수보고서에 넣을 작업 일정표는 WBS, 착수보고서 자체를 찾으면 KICKOFF입니다.
                어댑터 명세는 ADAPTER, 설계서는 DESIGN, 계획서는 PLAN, 시험서는 TEST, 가이드는 GUIDE, 제안서는 PROPOSAL입니다. 유형을 특정하지 않으면 OTHER입니다.
                IAM/SSO 사전조사서·사전조사표 작성에 참고할 양식이나 과거 조사서는 SURVEY입니다.
                사전조사서에 넣을 다른 산출물을 명시했다면 요청한 원본 유형을 선택하세요.
                JSON 배열 하나로 응답하세요. 첫 원소는 {"source":"위 유형 코드 또는 OTHER",
                "ids":["질문에 나온 고객사명","대상 시스템명"],"topics":["찾는 시트나 표 주제의 짧은 명사"]} 입니다.
                가이드·매뉴얼을 작성할 때 참고할 장절을 찾는 질문은 GUIDE입니다.
                topics에는 제목의 주제와 찾는 장절의 주제를 각각 짧은 명사로 넣으세요.
                식별어는 분리하고, 없는 명칭은 만들지 마세요. 나머지 원소는 일반 검색에 사용할 핵심 구절 문자열입니다.
                질문: """ + question;
    }

    static PmReference parse(JsonElement el){
        if(!el.isJsonObject())return null;
        var obj=el.getAsJsonObject();
        if(!obj.has("source")||!obj.get("source").isJsonPrimitive())return null;
        try{return new PmReference(Kind.valueOf(obj.get("source").getAsString()),strings(obj,"ids"),strings(obj,"topics"));}
        catch(IllegalArgumentException e){return null;}
    }
    static List<String> strings(JsonObject obj,String key){
        var values=new LinkedHashSet<String>();
        if(obj.has(key)&&obj.get(key).isJsonArray()) for(var item:obj.getAsJsonArray(key)){
            if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())continue;
            var word=item.getAsString().strip();
            if(!word.isEmpty()&&word.length()<=60)values.add(word);
            if(values.size()==5)break;
        }
        return List.copyOf(values);
    }
    private static final Pattern SEPARATORS=Pattern.compile("[\\s_·-]+");
    private static String fold(String s){return SEPARATORS.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");}
    private static int matches(String folded,List<String> words){
        int n=0;
        for(String word:words)if(!fold(word).isEmpty() && folded.contains(fold(word)))n++;
        return n;
    }
    private record Ranked(DocumentAgent.Claim claim,int score,int excerptScore){}
    List<DocumentAgent.Claim> search(Connection db,SearchIndex.Focus focus,String question)throws SQLException{
        // In a title such as "AD Adaptor", only the separate AD token identifies the system.
        Map<String,Pattern> idPatterns=new LinkedHashMap<>();
        for(String id:identifiers)if(id.matches("[A-Za-z0-9]{1,3}"))
            idPatterns.put(id,Pattern.compile("(?i)(?<![a-z0-9])"+Pattern.quote(id)+"(?![a-z0-9])"));
        var headingTerms=new LinkedHashSet<>(topics);
        for(String topic:topics)for(String part:topic.split("[\\s_-]+")){
            if(part.length()>1 && !List.of("항목","예시","시트","정보","정의","설계").contains(part))headingTerms.add(part);
        }
        var sectionClues=List.copyOf(headingTerms);
        var sectionPhrases=new LinkedHashSet<String>();
        for(String topic:topics){
            var parts=RelevanceSearch.words(topic);
            for(int i=1;i<parts.size();i++)sectionPhrases.add(parts.get(i-1)+parts.get(i));
        }
        // Keep relationships from the user's wording when the agent shortens or paraphrases a topic.
        var questionTerms=RelevanceSearch.words(question);
        for(int i=1;i<questionTerms.size();i++)
            sectionPhrases.add(questionTerms.get(i-1)+questionTerms.get(i));
        var phraseClues=List.copyOf(sectionPhrases);
        String title="lower(replace(replace(d.title,' ',''),'_',''))";
        String type=switch(kind){
            case WBS -> "("+title+" LIKE '%wbs%' OR "+title+" LIKE '%일정표%')";
            case ADAPTER -> "("+title+" LIKE '%설계%' AND ("+title+" LIKE '%어댑터%' OR "+title+" LIKE '%어뎁터%' OR "
                    +title+" LIKE '%adapter%' OR "+title+" LIKE '%adaptor%'))";
            case SURVEY -> "("+title+" LIKE '%사전조사%' OR replace(replace(lower(l.section),' ',''),'_','') LIKE '%사전조사%')";
            default -> "0";
        };
        // A manual role takes precedence, including an explicit unclassified selection.
        type="(d.role='"+kind.name()+"' OR (d.role='' AND "+type+"))";
        String sql="SELECT d.md,d.source,d.title,l.section,substr(l.text,1,2000) FROM visible_docs d JOIN lines l ON l.doc=d.id "
                +"WHERE "+type+" AND l.kind=2 AND l.text NOT LIKE '## %' AND l.text NOT LIKE '%| --- |%' "
                +(focus==null?"":"AND d.md=? ")+(focus!=null && focus.section()!=null?"AND l.section=? ":"")+"ORDER BY d.id,l.id";
        record Section(String md,String section){}
        Map<Section,Ranked> best=new LinkedHashMap<>();
        try(var stmt=db.prepareStatement(sql)){
            if(focus!=null){stmt.setString(1,focus.md().toString());if(focus.section()!=null)stmt.setString(2,focus.section());}
            try(var rs=stmt.executeQuery()){
                while(rs.next()){
                    String name=rs.getString(3),section=rs.getString(4),line=rs.getString(5);
                    String body=fold(line),heading=fold(section),file=fold(name);
                    int fileIds=0,rowIds=0;
                    for(String id:identifiers){
                        if(identifies(id,name,file,idPatterns))fileIds++;
                        if(identifies(id,line,body,idPatterns))rowIds++;
                    }
                    if(kind!=Kind.ADAPTER && kind!=Kind.WBS && identifiers.stream().anyMatch(id ->
                            !identifies(id,name,file,idPatterns) && !identifies(id,section,heading,idPatterns)
                                    && !identifies(id,line,body,idPatterns)))continue;
                    int sectionTopics=matches(heading,sectionClues);
                    int rowTopics=matches(body,topics),markers=matches(body,kind.markers);
                    int titleTopics=matches(file,sectionClues);
                    int score=20*fileIds+3*rowIds+9*sectionTopics+3*rowTopics+Math.min(markers,4)*2
                            + 8*titleTopics + 15*matches(heading,phraseClues);
                    if(!topics.isEmpty() && titleTopics+sectionTopics+rowTopics==0 && identifiers.isEmpty()
                            && kind!=Kind.WBS && kind!=Kind.ADAPTER && kind!=Kind.SURVEY)continue;
                    String identityCue="",authCue="";
                    if(kind==Kind.SURVEY){
                        String context=file+" "+heading+" "+body;
                        if(topics.contains("IAM"))identityCue=firstCue(context,List.of("통합계정","계정권한","계정관리","IAM","프로비저닝"));
                        if(topics.contains("SSO"))authCue=firstCue(context,List.of("통합인증","인증","SSO","로그인","SAML","OIDC"));
                        if((topics.contains("IAM")||topics.contains("SSO")) && identityCue.isEmpty() && authCue.isEmpty())continue;
                        if(!identityCue.isEmpty())score+=14;
                        if(!authCue.isEmpty())score+=14;
                    }
                    if(heading.matches(".*(표지|개정이력|작성가이드|history|holiday)$"))score-=12;
                    var key=new Section(rs.getString(1),section);
                    var previous=best.get(key);
                    // Rank the sheet on all its clues, but show a row that a PM can reuse.
                    int excerptScore=(markers>0?12:0)+2*markers+3*rowTopics+rowIds;
                    if(previous!=null){
                        score=Math.max(previous.score(),score);
                        if(previous.excerptScore()>=excerptScore){
                            if(previous.score()!=score)best.put(key,new Ranked(previous.claim(),score,previous.excerptScore()));
                            continue;
                        }
                    }
                    var hit=new Library.Hit(Path.of(key.md()),Path.of(rs.getString(2)),name,section,line);
                    var evidence=new ArrayList<SearchIndex.Evidence>();
                    String typeWord=switch(kind){case WBS->file.contains("wbs")?"WBS":"일정표";case ADAPTER->"설계";case SURVEY->"사전조사";default->kind.label;};
                    if(file.contains(fold(typeWord)))evidence.add(new SearchIndex.Evidence(0,typeWord,"문서명",name));
                    else if(heading.contains(typeWord))evidence.add(new SearchIndex.Evidence(0,typeWord,"구역명",section));
                    for(String domain:List.of(identityCue,authCue))if(!domain.isEmpty()){
                        boolean inName=file.contains(fold(domain)),inSection=heading.contains(fold(domain));
                        evidence.add(new SearchIndex.Evidence(0,domain,inName?"문서명":inSection?"구역명":"구역 원문",
                                inName?name:inSection?section:clip(line,domain)));
                    }
                    var missing=new ArrayList<String>();
                    if(kind==Kind.SURVEY){
                        if(topics.contains("IAM")&&identityCue.isEmpty())missing.add("IAM");
                        if(topics.contains("SSO")&&authCue.isEmpty())missing.add("SSO");
                    }
                    for(String id:identifiers){
                        if(identifies(id,name,file,idPatterns))evidence.add(new SearchIndex.Evidence(0,id,"문서명",name));
                        else if(identifies(id,section,heading,idPatterns))evidence.add(new SearchIndex.Evidence(0,id,"구역명",section));
                        else if(identifies(id,line,body,idPatterns))evidence.add(new SearchIndex.Evidence(0,id,"구역 원문",clip(line,id)));
                        else missing.add(id);
                    }
                    for(String topic:sectionClues)if(heading.contains(fold(topic)))evidence.add(new SearchIndex.Evidence(0,topic,"구역명",section));
                    String cue=java.util.stream.Stream.concat(kind.markers.stream(),topics.stream())
                            .filter(w->body.contains(fold(w))).findFirst().orElse("");
                    if(!cue.isEmpty())evidence.add(new SearchIndex.Evidence(0,cue,"구역 원문",line.length()>220?clip(line,cue):line));
                    String use=kind.label+" · "+section+(cue.isEmpty()?" (표 항목 미확인)":" · ‘"+cue+"’ 항목 참고");
                    best.put(key,new Ranked(new DocumentAgent.Claim(use,hit,List.copyOf(evidence),List.copyOf(missing),use),score,excerptScore));
                }
            }
        }
        var ordered=best.values().stream().sorted(Comparator.comparingInt(Ranked::score).reversed()).map(Ranked::claim);
        if(kind!=Kind.WBS && kind!=Kind.ADAPTER){
            var documents=new HashSet<Path>();
            ordered=ordered.filter(c->documents.add(c.proof().source()));
        }
        return ordered.limit(50).toList();
    }
    private static String firstCue(String text,List<String> candidates){
        for(String cue:candidates)if(text.contains(fold(cue)))return cue;
        return "";
    }
    private static boolean identifies(String id,String raw,String folded,Map<String,Pattern> patterns){
        Pattern token=patterns.get(id);
        return token==null?folded.contains(fold(id)):token.matcher(raw).find();
    }
    private static String clip(String line,String word){
        int pos=line.toLowerCase(Locale.ROOT).indexOf(word.toLowerCase(Locale.ROOT));
        if(pos<0)return line;
        return line.substring(Math.max(0,pos-38),Math.min(line.length(),pos+word.length()+75));
    }
}
