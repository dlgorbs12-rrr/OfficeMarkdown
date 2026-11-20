import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

final class OcrRepair {
    private static final Pattern BROKEN = Pattern.compile("[\uFFFD\\p{Co}]|[\\p{Cc}&&[^\\t\\r\\n]]|[?□]{2,}"
            + "|(?:Ã|Â|â)[\\u0080-\\u00BF\\u2000-\\u206F]");
    private static final Pattern JAMO = Pattern.compile("[\\u1100-\\u11FF\\u3131-\\u318E\\uA960-\\uA97F\\uD7B0-\\uD7FF]");
    private static final Pattern MIXED = Pattern.compile("[\\p{IsHan}\\p{IsHangul}]{3,120}");
    private static final Charset CP949 = Charset.forName("x-windows-949");

    static List<Library.Hit> find(Path library, int limit) throws IOException {
        return SearchIndex.suspectedOcr(library, limit);
    }

    static boolean suspected(String text) {
        if (BROKEN.matcher(text).find()) return true;

        // NFC composes normal decomposed Korean. Repeated incomplete syllables are a separate OCR signal.
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        var jamo = JAMO.matcher(normalized);
        if (jamo.find() && jamo.find()) return true;

        // Recognize UTF-8 Korean accidentally decoded as CP949 without treating all mixed CJK as corruption.
        var words = MIXED.matcher(normalized);
        while (words.find()) {
            String token = words.group();
            if (token.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                    || token.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL))
                continue;
            try {
                ByteBuffer bytes = CP949.newEncoder().encode(CharBuffer.wrap(token));
                String decoded = StandardCharsets.UTF_8.newDecoder().decode(bytes).toString();
                if (!decoded.equals(token) && decoded.codePoints().allMatch(c -> c >= 0xAC00 && c <= 0xD7A3))
                    return true;
            } catch (CharacterCodingException ignored) { /* The token does not match this encoding error. */ }
        }
        return false;
    }

    static int repair(Path library, List<Library.Hit> hits, ImageOcr paddle) throws IOException {
        return repair(library, hits, paddle::text);
    }

    static int repair(Path library, List<Library.Hit> hits, SavedPictureDocument.Recognizer recognizer) throws IOException {
        if (hits.isEmpty()) return 0;
        Path selectedNote = hits.getFirst().md().toAbsolutePath().normalize();
        var document = SavedPictureDocument.open(library, selectedNote);
        Map<PictureAssets.Reference, PictureAssets.Entry> images = new HashMap<>();
        for (var entry : document.entries()) images.put(document.reference(entry), entry);

        Map<String, PictureAssets.Entry> replacements = new HashMap<>();
        Set<PictureAssets.Reference> selected = new HashSet<>();
        for (var hit : hits) {
            var reference = hit.picture();
            var entry = images.get(reference);
            if (!hit.md().toAbsolutePath().normalize().equals(selectedNote)
                    || entry == null || !entry.section().equals(hit.section()) || !entry.text().equals(hit.excerpt()))
                throw SavedPictureDocument.stale();
            if (!selected.add(reference)) continue;
            SavedPictureDocument.checkInterrupted();
            var replacement = document.recognize(entry, document.pictureFile(entry), recognizer);
            if (replacement.state() == PictureAssets.OcrState.UNSUPPORTED)
                throw new IOException("저장된 그림을 읽을 수 없습니다.");
            String text = replacement.text();
            if (!text.isBlank() && !entry.text().equals(text) && !suspected(text))
                replacements.put(entry.filename(), replacement);
        }
        document.update(replacements);
        return replacements.size();
    }
}
