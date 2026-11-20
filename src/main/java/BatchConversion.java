import java.io.*;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class BatchConversion {
    enum Phase { SCANNING, PREVIEW, CONVERTING, INDEXING }

    record Progress(Phase phase, long scanned, long total, long converted, long skipped, long failed, String current,
                    int workers, long indexed, long deferred, long latestSkipped) {
        long processed() { return converted + skipped + failed; }
    }

    record Result(long converted, long skipped, long failed, long scanFailures, long warnings, List<String> details,
                  long indexed, long indexFailures, long deferred, long latestSkipped, boolean cancelled) {}

    @FunctionalInterface
    interface LatestPreviewConfirmation {
        boolean confirm(LatestVersionSelection.Preview preview) throws IOException;
    }

    private record Job(String key, Slot slot, String filename) {}
    private record Conversion(Path markdown, String failure, String warning) {}

    private static final String SOURCE_LINK = "[원본 파일 열기](<";
    private static final int DETAIL_LIMIT = 100;
    private static final long UPDATE_INTERVAL_NS = 100_000_000L;
    private static final int INDEX_BATCH_SIZE = 32;
    private static final long INDEX_INTERVAL_NS = 2_000_000_000L;
    private final Consumer<Progress> progress;
    private final List<String> details = new ArrayList<>();
    private final Set<Path> unindexed = new LinkedHashSet<>();
    private Phase phase = Phase.SCANNING;
    private long scanned, total, converted, skipped, failed, scanFailures, warnings;
    private long indexed, indexFailures, deferred, latestSkipped, pendingSince, lastUpdate;
    private int workers = 1;

    private BatchConversion(Consumer<Progress> progress) {
        this.progress = progress;
    }

    static Result run(List<Path> inputs, Path library, boolean reconvertSameName, ImageOcr.Mode mode,
                      int requestedWorkers, Consumer<Progress> progress) throws IOException {
        return run(inputs, library, reconvertSameName, mode, requestedWorkers, progress, null);
    }

    static Result run(List<Path> inputs, Path library, boolean reconvertSameName, ImageOcr.Mode mode,
                      int requestedWorkers, Consumer<Progress> progress, LatestPreviewConfirmation confirmation) throws IOException {
        var parallelism = new BatchParallelism(mode, requestedWorkers, Runtime.getRuntime().availableProcessors());
        return new BatchConversion(progress).run(inputs, library, reconvertSameName, mode, parallelism, confirmation);
    }

    private Result run(List<Path> inputs, Path library, boolean reconvertSameName, ImageOcr.Mode mode,
                       BatchParallelism parallelism, LatestPreviewConfirmation confirmation) throws IOException {
        publish("", true);
        // Keep the inventory on disk so the total is exact without holding every source path in memory.
        Path inventory = Files.createTempFile("office-markdown-batch-", ".tmp");
        try {
            var versions = confirmation == null ? null : new LatestVersionSelection();
            boolean folderInput = false;
            try (var paths = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(inventory)))) {
                for (Path input : inputs) folderInput |= scan(input, paths, versions);
            }
            long inventoryTotal = total;
            Set<Path> older = Set.of();
            if (versions != null && folderInput) {
                if (versions.hasGroups()) checkUnmarkedFiles(versions, inventory, inventoryTotal);
                var plan = versions.plan(inventoryTotal);
                checkInterrupted();
                phase = Phase.PREVIEW;
                publish("", true);
                if (!confirmation.confirm(plan.preview())) return result(true);
                checkInterrupted();
                older = plan.skippedPaths();
                latestSkipped = plan.preview().latestSkipped();
                total = plan.preview().selected();
            }
            Files.createDirectories(library);
            phase = Phase.SCANNING;
            Set<String> seenNames = reconvertSameName ? null : existingNames(library);
            phase = Phase.CONVERTING;
            workers = parallelism.observe();
            publish("", true);
            if (total != 0) convertInventory(inventory, inventoryTotal, library, seenNames, older, mode, parallelism);
            publish("", true);
            return result(false);
        } finally {
            Files.deleteIfExists(inventory);
        }
    }

    private Result result(boolean cancelled) {
        return new Result(converted, skipped, failed, scanFailures, warnings, List.copyOf(details), indexed, indexFailures,
                deferred, latestSkipped, cancelled);
    }

    private void checkUnmarkedFiles(LatestVersionSelection versions, Path inventory, long inventoryTotal) throws IOException {
        try (var paths = new DataInputStream(new BufferedInputStream(Files.newInputStream(inventory)))) {
            for (long i = 0; i < inventoryTotal; i++) {
                checkInterrupted();
                versions.checkUnmarked(readPath(paths));
            }
        }
    }

    private static Path readPath(DataInputStream paths) throws IOException {
        byte[] encoded = new byte[paths.readInt()];
        paths.readFully(encoded);
        return Path.of(new String(encoded, StandardCharsets.UTF_8));
    }

    private void convertInventory(Path inventory, long inventoryTotal, Path library, Set<String> seenNames,
                                  Set<Path> older, ImageOcr.Mode mode, BatchParallelism parallelism) throws IOException {
        Path scripts = mode == ImageOcr.Mode.OFF ? null : ocrScripts();
        try (var slots = new Slots(mode, scripts);
             var executor = Executors.newCachedThreadPool();
             var paths = new DataInputStream(new BufferedInputStream(Files.newInputStream(inventory)))) {
            var finished = new ExecutorCompletionService<Conversion>(executor);
            var running = new HashMap<Future<Conversion>, Job>();
            try {
                for (long i = 0; i < inventoryTotal; i++) {
                    checkInterrupted();
                    Path path = readPath(paths);
                    if (older.contains(path)) continue;
                    String key = seenNames == null ? path.toString().toLowerCase(Locale.ROOT) : filename(path);
                    while (true) {
                        flushIndex(library, false, currentFile(running));
                        if (seenNames != null && seenNames.contains(key)) {
                            skipped++;
                            publish(path.getFileName().toString(), false);
                            break;
                        }
                        int allowed = parallelism.observe();
                        boolean changed = workers != allowed;
                        workers = allowed;
                        slots.retireIdle(workers);
                        if (running.size() < workers && running.values().stream().noneMatch(job -> job.key().equals(key))) {
                            Slot slot = slots.acquire();
                            var submitted = finished.submit(() -> convert(path, library, slot.ocr));
                            running.put(submitted, new Job(key, slot, path.getFileName().toString()));
                            publish(path.getFileName().toString(), i == 0 || changed);
                            break;
                        }
                        publish(currentFile(running), changed);
                        complete(finished, running, seenNames, library);
                    }
                }
            } finally {
                try {
                    while (!running.isEmpty()) {
                        complete(finished, running, seenNames, library);
                        slots.retireIdle(running.size());
                    }
                } finally {
                    if (Thread.currentThread().isInterrupted()) executor.shutdownNow();
                    else flushIndex(library, true, "");
                }
            }
        }
    }

    private void complete(ExecutorCompletionService<Conversion> finished, Map<Future<Conversion>, Job> running,
                          Set<String> seenNames, Path library) throws IOException {
        try {
            // Periodically publish finished Markdown to the index even while a large document is still converting.
            var completed = finished.poll(200, TimeUnit.MILLISECONDS);
            if (completed != null) {
                Job job = running.remove(completed);
                job.slot().release();
                var result = completed.get();
                if (result.markdown() == null) {
                    failed++;
                    detail(result.failure());
                } else {
                    converted++;
                    if (seenNames != null) seenNames.add(job.key());
                    if (unindexed.isEmpty()) pendingSince = System.nanoTime();
                    unindexed.add(result.markdown());
                    if (result.warning() != null) {
                        warnings++;
                        detail(result.warning());
                    }
                }
            }
            flushIndex(library, false, currentFile(running));
            publish(currentFile(running), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("변환 작업이 중단되었습니다.");
        } catch (ExecutionException e) {
            throw new IOException("변환 작업을 완료하지 못했습니다.", e.getCause());
        }
    }

    private static String currentFile(Map<Future<Conversion>, Job> running) {
        return running.isEmpty() ? "" : running.values().iterator().next().filename();
    }

    private void flushIndex(Path library, boolean force, String convertingFile) {
        if (unindexed.isEmpty()) return;
        if (!force && unindexed.size() < INDEX_BATCH_SIZE && System.nanoTime() - pendingSince < INDEX_INTERVAL_NS) return;
        int count = unindexed.size();
        phase = Phase.INDEXING;
        publish(count + "개 문서", true);
        try {
            var completion = SearchIndex.indexCompletedDetailed(library, List.copyOf(unindexed));
            indexed += completion.indexed();
            deferred += completion.deferred();
            int missing = count - completion.indexed() - completion.deferred();
            indexFailures += missing;
            if (missing > 0) detail("색인에 추가하지 못한 변환본 " + missing + "건");
        } catch (IOException e) {
            indexFailures += count;
            detail("변환본 " + count + "건 색인 실패\n" + e);
        } finally {
            unindexed.clear();
            phase = Phase.CONVERTING;
            publish(convertingFile, true);
        }
    }

    private static Path ocrScripts() throws IOException {
        try {
            return ImageOcr.scripts();
        } catch (Exception e) {
            throw new IOException("OCR 도구 위치를 확인하지 못했습니다.", e);
        }
    }

    private boolean scan(Path input, DataOutputStream paths, LatestVersionSelection versions) throws IOException {
        checkInterrupted();
        Path root;
        try {
            root = input.toRealPath();
        } catch (IOException e) {
            scanned++;
            scanFailure(input, e);
            return false;
        }
        boolean directory = Files.isDirectory(root);
        if (!directory && !Library.supported(root)) {
            scanned++;
            scanFailure(root, new IOException("지원하지 않는 파일 형식"));
            return false;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                onScanned(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && Library.supported(path)) {
                    byte[] encoded = path.toString().getBytes(StandardCharsets.UTF_8);
                    paths.writeInt(encoded.length);
                    paths.write(encoded);
                    total++;
                    if (versions != null) versions.record(path, directory);
                }
                onScanned(path);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                onScanned(path);
                scanFailure(path, error);
                if (versions != null && directory && Library.supported(path)) versions.markIncomplete(path.getParent());
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) {
                if (error != null) {
                    scanFailure(dir, error);
                    if (versions != null) versions.markIncomplete(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return directory;
    }

    private void onScanned(Path path) throws InterruptedIOException {
        checkInterrupted();
        scanned++;
        publish(Objects.toString(path.getFileName(), ""), false);
    }

    private void scanFailure(Path path, IOException error) {
        scanFailures++;
        detail(path + " · 탐색 실패\n" + error);
    }

    private static Conversion convert(Path path, Path library, ImageOcr ocr) {
        try {
            if (ocr != null) ocr.warnings.clear();
            Path markdown = Library.convert(path, library, ocr);
            if (ocr == null || ocr.warnings.isEmpty()) return new Conversion(markdown, null, null);
            var warning = new StringBuilder().append(path).append(" · 변환 완료, 그림 처리 알림");
            for (String message : ocr.warnings) {
                warning.append('\n').append(message);
                if (warning.length() >= 4096) break;
            }
            return new Conversion(markdown, null, warning.toString());
        } catch (Exception e) {
            return new Conversion(null, path + "\n" + e, null);
        }
    }

    private Set<String> existingNames(Path library) throws IOException {
        Set<String> names = new HashSet<>();
        try (var files = Files.newDirectoryStream(library, path -> filename(path).endsWith(".md"))) {
            for (Path md : files) {
                checkInterrupted();
                if (!Files.isRegularFile(md, LinkOption.NOFOLLOW_LINKS)) continue;
                publish("기존 변환 확인", false);
                try (var in = Files.newBufferedReader(md)) {
                    String heading = in.readLine();
                    if (heading == null || !heading.startsWith("# ")) continue;
                    in.readLine();
                    String link = in.readLine();
                    if (link == null || !link.startsWith(SOURCE_LINK) || !link.endsWith(">)")) continue;
                    Path source = Path.of(URI.create(link.substring(SOURCE_LINK.length(), link.length() - 2)));
                    if (source.getFileName() != null && Library.supported(source)) names.add(filename(source));
                } catch (NoSuchFileException | CharacterCodingException | IllegalArgumentException | FileSystemNotFoundException ignored) {
                    // Ignore a removed or unrelated Markdown file.
                }
            }
        }
        return names;
    }

    private void detail(String message) {
        if (details.size() == DETAIL_LIMIT) return;
        details.add(message.length() <= 4096 ? message : message.substring(0, 4096) + "…");
    }

    private void publish(String current, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastUpdate < UPDATE_INTERVAL_NS) return;
        lastUpdate = now;
        progress.accept(new Progress(phase, scanned, total, converted, skipped, failed, current, workers, indexed, deferred,
                latestSkipped));
    }

    private static String filename(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("변환 작업이 중단되었습니다.");
    }

    private static final class Slot implements AutoCloseable {
        final ImageOcr ocr;
        boolean busy;
        long idleSince;

        Slot(ImageOcr ocr) { this.ocr = ocr; }

        void release() {
            busy = false;
            idleSince = System.nanoTime();
        }

        @Override public void close() throws IOException {
            if (ocr != null) ocr.close();
        }
    }

    private static final class Slots implements AutoCloseable {
        private static final long MAX_IDLE_NS = 15_000_000_000L;
        private final ImageOcr.Mode mode;
        private final Path scripts;
        private final List<Slot> slots = new ArrayList<>();

        Slots(ImageOcr.Mode mode, Path scripts) {
            this.mode = mode;
            this.scripts = scripts;
        }

        Slot acquire() {
            for (Slot slot : slots) {
                if (!slot.busy) {
                    slot.busy = true;
                    return slot;
                }
            }
            var slot = new Slot(scripts == null ? null : new ImageOcr(mode, scripts));
            slot.busy = true;
            slots.add(slot);
            return slot;
        }

        void retireIdle(int limit) throws IOException {
            long now = System.nanoTime();
            for (var iterator = slots.iterator(); iterator.hasNext();) {
                var slot = iterator.next();
                if (slot.busy || (slots.size() <= limit && now - slot.idleSince < MAX_IDLE_NS)) continue;
                iterator.remove();
                slot.close();
            }
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            for (Slot slot : slots) {
                try {
                    slot.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
