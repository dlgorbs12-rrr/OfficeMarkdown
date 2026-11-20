import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class OcrBackfill {
    record Result(int recognized, int skipped) {}

    record Status(int pending, int pictures) {}

    static int pending(Path library, Path note) throws IOException {
        return inspect(library, note).pending();
    }

    static Status inspect(Path library, Path note) throws IOException {
        if (PictureAssets.key(note) == null) return new Status(0, 0);
        var entries = SavedPictureDocument.open(library, note).entries();
        return new Status((int) entries.stream().filter(PictureAssets.Entry::needsOcr).count(), entries.size());
    }

    static Result run(Path library, Path note, ImageOcr ocr) throws IOException {
        return run(library, note, ocr.mode(), ocr::text);
    }

    static Result run(Path library, Path note, ImageOcr.Mode mode, SavedPictureDocument.Recognizer recognizer)
            throws IOException {
        if (mode == ImageOcr.Mode.OFF) throw new IOException("그림 글자 인식 방식을 선택하세요.");
        SavedPictureDocument.checkInterrupted();
        if (PictureAssets.key(note) == null) return new Result(0, 0);
        var document = SavedPictureDocument.open(library, note);
        var checkpoints = new OcrCheckpoints(mode, document.manifestStamp());
        Map<String, PictureAssets.Entry> replacements = new HashMap<>();
        int recognized = 0, skipped = 0;
        for (var entry : document.entries()) {
            if (!entry.needsOcr()) continue;
            SavedPictureDocument.checkInterrupted();
            Path picture = document.pictureFile(entry);
            String stamp = PictureAssets.fileStamp(picture);
            var replacement = checkpoints.read(entry, picture, stamp);
            if (replacement == null) {
                replacement = document.recognize(entry, picture, recognizer);
                if (!stamp.equals(PictureAssets.fileStamp(picture))) throw SavedPictureDocument.stale();
                checkpoints.write(replacement, picture, stamp);
            }
            if (replacement.state() == PictureAssets.OcrState.UNSUPPORTED) skipped++;
            else recognized++;
            replacements.put(entry.filename(), replacement);
        }
        document.update(replacements);
        return new Result(recognized, skipped);
    }
}
