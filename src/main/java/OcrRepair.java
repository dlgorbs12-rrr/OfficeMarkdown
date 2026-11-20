import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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

    @FunctionalInterface
    interface Recognizer {
        String text(byte[] data, BufferedImage image) throws IOException;
    }

    static int repair(Path library, List<Library.Hit> hits, Recognizer recognizer) throws IOException {
        if (hits.isEmpty()) return 0;
        Path root = library.toRealPath();
        Path selectedNote = hits.getFirst().md().toAbsolutePath().normalize();
        if (!root.equals(selectedNote.getParent().toRealPath()) || PictureAssets.key(selectedNote) == null)
            throw new IOException("변환본 폴더에서 선택된 문서를 찾을 수 없습니다.");
        Path note = root.resolve(selectedNote.getFileName());
        var attrs = Files.readAttributes(note, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || !note.equals(note.toRealPath())) throw stale();
        String generation = currentGeneration(note);
        if (generation == null) throw stale();

        String prefix = PictureAssets.prefix(PictureAssets.key(note), generation);
        List<PictureAssets.Entry> existing = PictureAssets.repairEntries(note, generation);
        Map<PictureAssets.Reference, PictureAssets.Entry> images = new HashMap<>();
        for (var entry : existing)
            images.put(new PictureAssets.Reference(prefix + entry.filename(), entry.location()), entry);

        Map<String, String> replacements = new HashMap<>();
        Set<PictureAssets.Reference> selected = new HashSet<>();
        for (var hit : hits) {
            var reference = hit.picture();
            var entry = images.get(reference);
            if (!hit.md().toAbsolutePath().normalize().equals(selectedNote)
                    || entry == null || !entry.section().equals(hit.section()) || !entry.text().equals(hit.excerpt()))
                throw stale();
            if (!selected.add(reference)) continue;
            checkInterrupted();
            Path imageFile = PictureAssets.pictureFile(note, reference);
            if (imageFile == null) throw stale();
            byte[] data = Files.readAllBytes(imageFile);
            String type = OfficePicture.type(data, entry.filename().substring(entry.filename().lastIndexOf('.') + 1));
            BufferedImage image = OfficePicture.decode(data, type);
            if (image == null) throw new IOException("저장된 그림을 읽을 수 없습니다.");
            String text = recognizer.text(data, image);
            if (!text.isBlank() && !entry.text().equals(text) && !suspected(text))
                replacements.put(entry.filename(), text);
        }
        checkInterrupted();
        if (replacements.isEmpty()) return 0;

        Path temp = Files.createTempFile(root, ".ocr-repair-", ".tmp");
        try (var staged = new PictureAssets(note, null)) {
            List<PictureAssets.Entry> updated = new ArrayList<>(existing.size());
            for (var entry : existing)
                updated.add(new PictureAssets.Entry(entry.filename(), entry.section(), entry.location(),
                        replacements.getOrDefault(entry.filename(), entry.text())));
            rewrite(note, temp, generation, images, replacements, staged);
            staged.stageRepair(generation, updated);
            checkInterrupted();
            staged.publishRepair(temp, generation, attrs);
            return replacements.size();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String currentGeneration(Path note) throws IOException {
        try (var in = Files.newBufferedReader(note)) {
            for (int i = 0; i < 7; i++) {
                String generation = PictureAssets.generation(in.readLine());
                if (generation != null) return generation;
            }
        }
        return null;
    }

    private static void rewrite(Path note, Path temp, String generation,
                                Map<PictureAssets.Reference, PictureAssets.Entry> images,
                                Map<String, String> replacements, PictureAssets staged) throws IOException {
        Map<String, PictureAssets.Entry> oldLines = new HashMap<>();
        images.forEach((reference, entry) -> oldLines.put(PictureAssets.markdownLine(reference), entry));
        Set<String> seen = new HashSet<>();
        boolean header = false;
        try (var in = Files.newBufferedReader(note); var out = Files.newBufferedWriter(temp)) {
            String line;
            int lineNumber = 0;
            while ((line = in.readLine()) != null) {
                if ((++lineNumber & 0x3FF) == 0) checkInterrupted();
                if (lineNumber <= 7 && generation.equals(PictureAssets.generation(line))) {
                    if (header) throw stale();
                    header = true;
                    line = staged.header();
                }
                var entry = oldLines.get(line);
                if (entry == null) {
                    out.write(line);
                    out.write('\n');
                    continue;
                }
                if (!seen.add(line)) throw stale();
                var reference = staged.reference(entry);
                out.write(PictureAssets.markdownLine(reference));
                out.write('\n');
                String replacement = replacements.get(entry.filename());
                if (replacement == null) continue;

                String caption = "그림 " + reference.number() + " · " + entry.location();
                requireLine(in, caption);
                requireLine(in, "");
                // Compare the stored OCR block before replacing it.
                var previous = PictureAssets.ocrBlock(entry.location(), entry.text()).lines().iterator();
                while (previous.hasNext()) requireLine(in, previous.next());
                out.write(caption);
                out.write("\n\n");
                out.write(PictureAssets.ocrBlock(entry.location(), replacement));
            }
        }
        if (!header || seen.size() != oldLines.size()) throw stale();
    }

    private static void requireLine(BufferedReader in, String expected) throws IOException {
        if (!expected.equals(in.readLine())) throw stale();
    }

    private static IOException stale() {
        return new IOException("변환본 또는 그림 정보가 바뀌었습니다. OCR 목록을 다시 확인하세요.");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("OCR 재인식을 중단했습니다.");
    }
}
