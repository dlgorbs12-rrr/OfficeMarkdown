import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RelevanceSearchTest {
    private static final Pattern ORIGINAL_SEPARATORS = Pattern.compile("[\\p{Z}\\s_·./()\\[\\]-]+");

    @Test void normalizationPreservesIndexedSearchSemantics() {
        for (int start = 0; start <= Character.MAX_CODE_POINT; start += 4096) {
            StringBuilder text = new StringBuilder();
            int end = Math.min(start + 4096, Character.MAX_CODE_POINT + 1);
            for (int point = start; point < end; point++) text.appendCodePoint(point);
            assertSameNormalization(text.toString());
        }
        assertSameNormalization("Ａİ.ΟΣ 한국어\u00a0/·-A__B[구성](도)\u2028\u001c");
        assertSameNormalization("");
    }

    private static void assertSameNormalization(String input) {
        String expected = ORIGINAL_SEPARATORS.matcher(input.toLowerCase(Locale.ROOT)).replaceAll("");
        assertEquals(expected, RelevanceSearch.compact(input));
    }
}