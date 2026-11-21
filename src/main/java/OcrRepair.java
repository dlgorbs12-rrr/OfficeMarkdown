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
    private static final Pattern SPACED_HANGUL=Pattern.compile("(?<![가-힣])(?:[가-힣][ \\t]+){7}[가-힣](?![가-힣])");
    private static final Charset CP949 = Charset.forName("x-windows-949");

    static List<Library.Hit> find(Path library, int limit) throws IOException {
        return SearchIndex.suspectedOcr(library, limit);
    }

    static List<Library.Hit> documentPictures(Path note)throws IOException{
        var document=SavedPictureDocument.open(note.getParent(),note);
        var hits=new ArrayList<Library.Hit>();
        for(var entry:document.entries()){
            if(entry.state()!=PictureAssets.OcrState.UNSUPPORTED)
                hits.add(new Library.Hit(note,note,note.getFileName().toString(),entry.section(),entry.text(),document.reference(entry)));
        }
        return hits;
    }

    static boolean lowQuality(String text,String location){
        return !qualityReason(text,location).isEmpty();
    }

    static String qualityReason(String text,String location){
        if(suspected(text))return "문자 깨짐 또는 분리된 자모";
        if(location.endsWith(" · 스캔") && text.codePoints().filter(Character::isLetterOrDigit).limit(4).count()<4)
            return "스캔 페이지에서 인식한 글자가 4자 미만";
        if(SPACED_HANGUL.matcher(text).find())return "한글 음절이 연속으로 분리됨";
        return "";
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
            if (!text.isBlank() && !entry.text().equals(text) && !suspected(text) && !SPACED_HANGUL.matcher(text).find())
                replacements.put(entry.filename(), replacement);
        }
        document.update(replacements);
        return replacements.size();
    }
}
