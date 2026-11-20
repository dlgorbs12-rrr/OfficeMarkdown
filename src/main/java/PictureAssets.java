import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

final class PictureAssets implements AutoCloseable {
    private static final String FOLDER = ".office-images";
    private static final String HEADER = "<!-- office-images: ";
    private static final String MANIFEST = "images.jsonl";
    private static final Gson JSON = new Gson();
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{20}");
    private static final Pattern GENERATION = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern FILE = Pattern.compile("([1-9][0-9]{0,8})\\.([a-z0-9]{1,8})");
    private static final Pattern DERIVED = Pattern.compile("[1-9][0-9]{0,8}-(preview|thumb)\\.png");

    record Entry(String filename, String section, String location, String text) {}

    record Reference(String relativePath, String location) {
        String number() { return relativePath.substring(relativePath.lastIndexOf('/') + 1, relativePath.lastIndexOf('.')); }
    }

    private final Path target;
    private final ImageOcr ocr;
    private final String key;
    private final String generation = UUID.randomUUID().toString().replace("-", "");
    private final Path directory;
    private BufferedWriter manifest;
    private int counter;
    private boolean created;
    private boolean published;

    PictureAssets(Path target, ImageOcr ocr) {
        this.target = target;
        this.ocr = ocr;
        key = key(target);
        directory = target.getParent().resolve(prefix(key, generation));
    }

    void writeHeader(Writer out) throws IOException { out.write("\n" + header() + "\n"); }

    private void openManifest() throws IOException {
        directory(directory.getParent().getParent());
        directory(directory.getParent());
        Files.createDirectory(directory);
        created = true;
        manifest = Files.newBufferedWriter(directory.resolve(MANIFEST), StandardOpenOption.CREATE_NEW);
    }

    void write(byte[] data, String hint, String section, String location, Writer out) throws IOException {
        if (manifest == null) openManifest();
        String type = OfficePicture.type(data, Objects.toString(hint, "bin"));
        String extension = type.toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,8}")) extension = "bin";
        int number = ++counter;
        String filename = number + "." + extension;
        Files.write(directory.resolve(filename), data, StandardOpenOption.CREATE_NEW);

        location = location.replaceAll("\\R", " ");
        BufferedImage bitmap = decode(data, type, location);
        String recognized = bitmap == null || ocr == null ? "" : ocr.text(data, bitmap);
        if (bitmap != null) {
            ImageIO.write(scale(bitmap, 1280, 960), "PNG", directory.resolve(number + "-preview.png").toFile());
            ImageIO.write(scale(bitmap, 72, 48), "PNG", directory.resolve(number + "-thumb.png").toFile());
        }
        writeEntry(new Entry(filename, section, location, recognized));
        var reference = new Reference(prefix(key, generation) + filename, location);
        out.write("\n" + markdownLine(reference) + "\n그림 " + number + " · " + location + "\n\n");
        out.write(ocrBlock(location, recognized));
    }

    static String ocrBlock(String location, String text) {
        return text.isBlank() ? "" : "그림 OCR · " + location + "\n\n" + Library.plain(text) + "\n\n";
    }

    private void writeEntry(Entry entry) throws IOException {
        manifest.write(JSON.toJson(entry));
        manifest.newLine();
    }

    Reference reference(Entry entry) {
        return new Reference(prefix(key, generation) + entry.filename(), entry.location());
    }

    String header() { return HEADER + generation + " -->"; }

    void stageRepair(String previous, List<Entry> entries) throws IOException {
        openManifest();
        String previousPrefix = prefix(key, previous);
        for (var entry : entries) {
            Path original = pictureFile(target, new Reference(previousPrefix + entry.filename(), entry.location()));
            if (original == null) throw new IOException("저장된 그림을 찾을 수 없습니다.");
            copyImage(original, entry.filename());
            String stem = entry.filename().substring(0, entry.filename().indexOf('.'));
            for (String suffix : List.of("-preview.png", "-thumb.png")) {
                String name = stem + suffix;
                Path image = localFile(target, previousPrefix + name);
                if (image != null) copyImage(image, name);
            }
            writeEntry(entry);
        }
    }

    private void copyImage(Path source, String name) throws IOException {
        Path destination = directory.resolve(name);
        // Published image generations are immutable. Link when available to avoid copying image bytes.
        try { Files.createLink(destination, source); }
        catch (UnsupportedOperationException | FileSystemException e) { Files.copy(source, destination); }
    }

    private BufferedImage decode(byte[] data, String type, String location) {
        try {
            var image = OfficePicture.decode(data, type);
            if (image == null && ocr != null) ocr.warnings.add(location + " · 지원하지 않는 그림 형식: " + type);
            return image;
        } catch (IOException | RuntimeException e) {
            if (ocr != null) ocr.warnings.add(location + " · " + type + " 그림 해석 실패 (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage image, int maxWidth, int maxHeight) {
        double ratio = Math.min(1, Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight()));
        if (ratio == 1 && (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB)) return image;
        int width = Math.max(1, (int) (image.getWidth() * ratio));
        int height = Math.max(1, (int) (image.getHeight() * ratio));
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return result;
    }

    static String markdownLine(Reference reference) {
        return "![그림 " + reference.number() + "](" + reference.relativePath() + ")";
    }

    void publish(Path markdown) throws IOException {
        publish(markdown, null, null);
    }

    void publishRepair(Path markdown, String previous, BasicFileAttributes expected) throws IOException {
        publish(markdown, previous, expected);
    }

    private void publish(Path markdown, String expectedGeneration, BasicFileAttributes expected) throws IOException {
        if (manifest != null) {
            manifest.close();
            manifest = null;
        }
        Path assets = directory.getParent().getParent();
        // Coordinate only publication. Each converter writes a unique generation before taking the lock.
        synchronized (PictureAssets.class) {
            if (!Files.exists(assets, LinkOption.NOFOLLOW_LINKS)) {
                if (expected != null) throw new IOException("변환본이 바뀌었습니다. 깨짐 목록을 다시 확인하세요.");
                replace(markdown, target);
                published = true;
                return;
            }
            directory(assets);
            try (var channel = FileChannel.open(assets.resolve(".commit.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.lock()) {
                String previous = previousGeneration();
                if (expected != null) {
                    var current = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!Objects.equals(previous, expectedGeneration) || !current.isRegularFile()
                            || current.size() != expected.size() || !current.lastModifiedTime().equals(expected.lastModifiedTime())
                            || !Objects.equals(current.fileKey(), expected.fileKey()))
                        throw new IOException("변환본이 바뀌었습니다. 깨짐 목록을 다시 확인하세요.");
                }
                replace(markdown, target);
                published = true;
                if (previous != null) {
                    try { deleteGeneration(target.getParent().resolve(prefix(key, previous))); }
                    catch (IOException e) {
                        if (ocr != null) ocr.warnings.add("이전 그림 폴더 정리 실패");
                    }
                }
            }
        }
    }

    private String previousGeneration() {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var in = Files.newBufferedReader(target)) {
            for (int line = 0; line < 7; line++) {
                String generation = generation(in.readLine());
                if (generation != null) return generation;
            }
        } catch (IOException ignored) { /* A conversion can replace an unreadable old note. */ }
        return null;
    }

    private static void replace(Path markdown, Path target) throws IOException {
        try { Files.move(markdown, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(markdown, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void directory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(path); }
            catch (FileAlreadyExistsException ignored) { /* Check the existing entry below. */ }
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath()))
            throw new IOException("그림 저장 폴더가 올바르지 않습니다.");
    }

    private static void deleteGeneration(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())) return;
        try (var files = Files.newDirectoryStream(path)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.equals(MANIFEST) || FILE.matcher(name).matches() || DERIVED.matcher(name).matches())
                    if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
            }
        }
        Files.delete(path);
    }

    @Override public void close() throws IOException {
        try {
            if (manifest != null) manifest.close();
        } finally {
            if (created && !published) deleteGeneration(directory);
        }
    }

    static String generation(String line) {
        if (line == null || !line.startsWith(HEADER) || !line.endsWith(" -->")) return null;
        String value = line.substring(HEADER.length(), line.length() - 4);
        return GENERATION.matcher(value).matches() ? value : null;
    }

    static String key(Path markdown) {
        String name = markdown.getFileName().toString();
        if (name.length() < 24 || name.charAt(name.length() - 24) != '-') return null;
        String value = name.substring(name.length() - 23, name.length() - 3);
        return KEY.matcher(value).matches() ? value : null;
    }

    static String prefix(String key, String generation) { return FOLDER + "/" + key + "/" + generation + "/"; }

    static String manifestStamp(Path markdown, String generation) {
        try {
            Path file = manifestFile(markdown, generation);
            if (file != null) {
                var attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return attrs.lastModifiedTime() + "/" + attrs.creationTime() + "/" + attrs.size() + "/" + attrs.fileKey();
            }
        } catch (IOException ignored) { /* The manifest is unavailable. */ }
        return "";
    }

    static List<Entry> entries(Path markdown, String generation) {
        try { return readEntries(markdown, generation, false); }
        catch (IOException ignored) { return List.of(); }
    }

    static List<Entry> repairEntries(Path markdown, String generation) throws IOException {
        return readEntries(markdown, generation, true);
    }

    private static List<Entry> readEntries(Path markdown, String generation, boolean strict) throws IOException {
        List<Entry> images = new ArrayList<>();
        Path file = manifestFile(markdown, generation);
        if (file == null) return List.of();
        Set<String> names = strict ? new HashSet<>() : null;
        try (var in = Files.newBufferedReader(file)) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    Entry entry = JSON.fromJson(line, Entry.class);
                    if (entry != null && entry.filename() != null && FILE.matcher(entry.filename()).matches()
                            && entry.section() != null && entry.location() != null && entry.text() != null
                            && (!strict || names.add(entry.filename()))) {
                        images.add(entry);
                        continue;
                    }
                } catch (JsonParseException ignored) { /* An invalid entry is handled below. */ }
                if (strict) throw new IOException("그림 정보가 손상되었습니다. 원본을 다시 변환하세요.");
            }
        }
        return images;
    }

    private static Path manifestFile(Path markdown, String generation) throws IOException {
        String key = key(markdown);
        if (key == null || generation == null || !GENERATION.matcher(generation).matches()) return null;
        return localFile(markdown, prefix(key, generation) + MANIFEST);
    }

    static BufferedImage preview(Path markdown, Reference reference, boolean thumbnail) {
        String generation = referenceGeneration(markdown, reference);
        if (generation == null) return null;
        try {
            Path file = localFile(markdown, prefix(key(markdown), generation) + reference.number()
                    + (thumbnail ? "-thumb.png" : "-preview.png"));
            return file == null ? null : ImageIO.read(file.toFile());
        } catch (IOException | RuntimeException ignored) { return null; }
    }

    static String referenceGeneration(Path markdown, Reference reference) {
        String[] parts = reference.relativePath().split("/", -1);
        if (parts.length != 4 || !parts[0].equals(FOLDER) || !parts[1].equals(key(markdown))
                || !GENERATION.matcher(parts[2]).matches() || !FILE.matcher(parts[3]).matches()) return null;
        return parts[2];
    }

    static Path pictureFile(Path markdown, Reference reference) throws IOException {
        return referenceGeneration(markdown, reference) == null ? null : localFile(markdown, reference.relativePath());
    }

    private static Path localFile(Path markdown, String relative) throws IOException {
        Path parent = markdown.toAbsolutePath().getParent();
        Path file = parent.resolve(relative);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try { return parent.toRealPath().resolve(relative).equals(file.toRealPath()) ? file : null; }
        catch (NoSuchFileException ignored) { return null; }
    }
}
