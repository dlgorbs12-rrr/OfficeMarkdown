import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

final class SavedPictureDocument {
    @FunctionalInterface
    interface Recognizer {
        String text(byte[] data, BufferedImage image) throws IOException;
    }

    private final Path note;
    private final BasicFileAttributes attributes;
    private final String generation;
    private final String manifestStamp;
    private final List<PictureAssets.Entry> entries;

    private SavedPictureDocument(Path note, BasicFileAttributes attributes, String generation,
                                 String manifestStamp, List<PictureAssets.Entry> entries) {
        this.note = note;
        this.attributes = attributes;
        this.generation = generation;
        this.manifestStamp = manifestStamp;
        this.entries = entries;
    }

    static SavedPictureDocument open(Path library, Path selected) throws IOException {
        Path root = library.toRealPath();
        Path path = selected.toAbsolutePath().normalize();
        if (path.getParent() == null || !root.equals(path.getParent().toRealPath())
                || PictureAssets.key(path) == null || !path.getFileName().toString().endsWith(".md"))
            throw new IOException("변환본 폴더에서 선택된 문서를 찾을 수 없습니다.");
        Path note = root.resolve(path.getFileName());
        var attributes = Files.readAttributes(note, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || !note.equals(note.toRealPath())) throw stale();
        String generation = PictureAssets.currentGeneration(note);
        String manifestStamp = PictureAssets.manifestStamp(note, generation);
        var entries = PictureAssets.repairEntries(note, generation);
        if (!manifestStamp.equals(PictureAssets.manifestStamp(note, generation))) throw stale();
        return new SavedPictureDocument(note, attributes, generation, manifestStamp, entries);
    }

    List<PictureAssets.Entry> entries() { return entries; }

    String manifestStamp() { return manifestStamp; }

    PictureAssets.Reference reference(PictureAssets.Entry entry) {
        return new PictureAssets.Reference(PictureAssets.prefix(PictureAssets.key(note), generation)
                + entry.filename(), entry.location());
    }

    Path pictureFile(PictureAssets.Entry entry) throws IOException {
        Path imageFile = PictureAssets.pictureFile(note, reference(entry));
        if (imageFile == null) throw stale();
        return imageFile;
    }

    PictureAssets.Entry recognize(PictureAssets.Entry entry, Path imageFile, Recognizer recognizer) throws IOException {
        checkInterrupted();
        byte[] data = Files.readAllBytes(imageFile);
        String type = OfficePicture.type(data, entry.filename().substring(entry.filename().lastIndexOf('.') + 1));
        var image = OfficePicture.decode(data, type);
        checkInterrupted();
        if (image == null) return entry.withOcr("", PictureAssets.OcrState.UNSUPPORTED);
        String recognized = recognizer.text(data, image);
        return entry.withOcr(recognized.isBlank() ? "" : recognized, PictureAssets.OcrState.COMPLETE);
    }

    void update(Map<String, PictureAssets.Entry> replacements) throws IOException {
        checkInterrupted();
        if (replacements.isEmpty()) return;
        Path temp = Files.createTempFile(note.getParent(), ".ocr-update-", ".tmp");
        try (var staged = new PictureAssets(note, null)) {
            rewrite(temp, replacements, staged);
            List<PictureAssets.Entry> updated = entries.stream()
                    .map(entry -> replacements.getOrDefault(entry.filename(), entry)).toList();
            staged.stageOcrUpdate(generation, updated);
            staged.publishOcrUpdate(temp, generation, attributes, manifestStamp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void rewrite(Path temp, Map<String, PictureAssets.Entry> replacements, PictureAssets staged)
            throws IOException {
        Map<String, PictureAssets.Entry> oldLines = new HashMap<>();
        for (var entry : entries) oldLines.put(PictureAssets.markdownLine(reference(entry)), entry);
        Set<String> seen = new HashSet<>();
        boolean header = false;
        try (var in = Files.newBufferedReader(note); var out = Files.newBufferedWriter(temp)) {
            var lines = new MarkdownLines(in);
            Line line;
            int lineNumber = 0;
            while ((line = lines.next()) != null) {
                if (++lineNumber <= 7 && generation.equals(PictureAssets.generation(line.text()))) {
                    if (header) throw stale();
                    header = true;
                    line.write(out, staged.header());
                    continue;
                }
                var entry = oldLines.get(line.text());
                if (entry == null) {
                    line.write(out, line.text());
                    continue;
                }
                if (!seen.add(line.text())) throw stale();
                var reference = staged.reference(entry);
                line.write(out, PictureAssets.markdownLine(reference));
                var replacement = replacements.get(entry.filename());
                if (replacement == null || entry.text().equals(replacement.text())) continue;

                Line caption = lines.require(PictureAssets.caption(reference));
                caption.write(out, caption.text());
                lines.require("").write(out, "");
                var previous = PictureAssets.ocrBlock(entry.location(), entry.text()).lines().iterator();
                while (previous.hasNext()) lines.require(previous.next());
                out.write(PictureAssets.ocrBlock(entry.location(), replacement.text()).replace("\n", caption.ending()));
            }
        }
        if (!header || seen.size() != oldLines.size()) throw stale();
    }

    private record Line(String text, String ending) {
        void write(Writer out, String value) throws IOException {
            out.write(value);
            out.write(ending);
        }
    }

    // Preserve original line separators and a missing final newline while streaming the body.
    private static final class MarkdownLines {
        private final Reader reader;
        private final char[] buffer = new char[8192];
        private int offset;
        private int limit;

        MarkdownLines(Reader reader) { this.reader = reader; }

        Line require(String expected) throws IOException {
            Line line = next();
            if (line == null || !expected.equals(line.text())) throw stale();
            return line;
        }

        Line next() throws IOException {
            StringBuilder partial = null;
            while (fill()) {
                int start = offset;
                while (offset < limit && buffer[offset] != '\n' && buffer[offset] != '\r') offset++;
                int length = offset - start;
                if (offset < limit) {
                    String text = partial == null ? new String(buffer, start, length)
                            : partial.append(buffer, start, length).toString();
                    if (buffer[offset++] == '\n') return new Line(text, "\n");
                    if (fill() && buffer[offset] == '\n') {
                        offset++;
                        return new Line(text, "\r\n");
                    }
                    return new Line(text, "\r");
                }
                if (partial == null) partial = new StringBuilder(length);
                partial.append(buffer, start, length);
            }
            return partial == null ? null : new Line(partial.toString(), "");
        }

        private boolean fill() throws IOException {
            if (offset < limit) return true;
            if (limit < 0) return false;
            checkInterrupted();
            limit = reader.read(buffer);
            offset = 0;
            return limit > 0;
        }
    }

    static IOException stale() {
        return new IOException("변환본 또는 그림 정보가 바뀌었습니다. OCR 목록을 다시 확인하세요.");
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("저장된 그림 OCR 작업을 중단했습니다.");
    }
}
