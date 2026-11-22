import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Direct document lookup for a named system. More complex requests stay in the agent conversation. */
final class AdapterDiscovery {
    private static final Pattern LOOKUP = Pattern.compile(
        "^\\s*(?:기존\\s*)?(Active[\\s_-]*Directory|[A-Za-z][A-Za-z0-9]{0,11})\\s*"
        + "(?:어댑터|어뎁터|어댑타|adapter|adaptor)\\s*설계(?:서)?"
        + "(?:의|를|을)?\\s*(?:(?:초본|초안)(?:을|를)?\\s*)?"
        + "(?:(?:만들기|작성하기|작성)(?:를)?\\s*(?:위한|위해|용)?\\s*)?"
        + "(?:(?:참고(?:할|용)?|기존)\\s*)?(?:(?:자료|문서|설계서)(?:를|가)?\\s*)?"
        + "(?:탐색|검색|조회|찾아|찾기|찾고|보여|필요)(?:\\s*(?:해|할|줘|주세요|해줘|해요|싶어|합니다))*[.!?？]*\\s*$",
        Pattern.CASE_INSENSITIVE);

    static PmReference plan(String question) {
        if (question == null || question.length() > 400) return null;
        var match = LOOKUP.matcher(question);
        if (!match.matches()) return null;
        String target = match.group(1);
        if (target.replaceAll("[\\s_-]", "").equalsIgnoreCase("ActiveDirectory")) target = "AD";
        return new PmReference(PmReference.Kind.ADAPTER,List.of(target),List.of());
    }

    static McpSearch.Reply find(PmReference plan, SearchIndex.Focus focus, String question,List<String> notes) throws IOException {
        var matches = SearchIndex.readIndex(notes,db -> plan.search(db,focus,question,true));
        var claims = new ArrayList<DocumentAgent.Claim>();
        var text = new StringJoiner("\n\n");
        for (var match : matches.stream().limit(8).toList()) {
            var hit = match.proof();
            String id = "E" + (claims.size()+1);
            claims.add(new DocumentAgent.Claim(id,hit,match.evidence(),match.missing(),match.referenceUse()));
            text.add(hit.title()+" · "+hit.section()+" ["+id+"]");
        }
        if (claims.isEmpty()) text.add("현재 검색 범위에서 "+plan.identifiers().getFirst()+" 대상이 명시된 어댑터 설계서를 찾지 못했습니다.");
        return new McpSearch.Reply(text.toString(),List.copyOf(claims));
    }
}