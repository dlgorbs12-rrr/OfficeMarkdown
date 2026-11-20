import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.regex.Pattern;

final class OcrCheckpoints {
    private static final Gson JSON = new Gson();
    private static final Pattern FILE = Pattern.compile("[1-9][0-9]{0,8}\\.[a-z0-9]{1,8}-ocr-(windows|paddle|vision)\\.json"
            + "|\\.ocr-[0-9]{1,20}\\.tmp");

    private record Saved(String manifest, String asset, String text, PictureAssets.OcrState state) {}

    private final String engine;
    private final String manifest;

    OcrCheckpoints(ImageOcr.Mode mode, String manifest) {
        this.engine = mode.name().toLowerCase(Locale.ROOT);
        this.manifest = manifest;
    }

    static boolean filename(String name) { return FILE.matcher(name).matches(); }

    private Path checkpoint(Path picture) {
        return picture.resolveSibling(picture.getFileName() + "-ocr-" + engine + ".json");
    }

    PictureAssets.Entry read(PictureAssets.Entry entry, Path picture, String asset) throws IOException {
        Path file = checkpoint(picture);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var in = new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
            Saved saved = JSON.fromJson(in, Saved.class);
            if (saved == null || !manifest.equals(saved.manifest()) || !asset.equals(saved.asset()) || saved.text() == null
                    || (saved.state() != PictureAssets.OcrState.COMPLETE && saved.state() != PictureAssets.OcrState.UNSUPPORTED))
                return null;
            return entry.withOcr(saved.text(), saved.state());
        } catch (JsonSyntaxException e) {
            return null;
        } catch (JsonIOException e) {
            throw new IOException("중단된 OCR 결과를 읽을 수 없습니다.", e);
        }
    }

    void write(PictureAssets.Entry entry, Path picture, String asset) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            // Persist a completed response even if cancellation arrived just after the engine returned.
            Path temp = Files.createTempFile(picture.getParent(), ".ocr-", ".tmp");
            try {
                Files.writeString(temp, JSON.toJson(new Saved(manifest, asset, entry.text(), entry.state())));
                Files.move(temp, checkpoint(picture), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
