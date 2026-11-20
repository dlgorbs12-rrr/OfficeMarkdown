import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

final class ReleaseUpdate {
    private static final String REPOSITORY = "dlgorbs12-rrr/OfficeMarkdown";
    private static final URI LATEST = URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
    private static final String RELEASES = "https://github.com/" + REPOSITORY + "/releases/";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern VERSION = Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern CDN_PATH = Pattern.compile("/github-production-release-asset/[0-9]+/[0-9a-fA-F-]{36}");
    private static final String TEMP_PREFIX = "officemarkdown-update-";
    private static final Pattern TEMP_NAME = Pattern.compile(TEMP_PREFIX + "[0-9]+");
    private static final Pattern INSTALLER_NAME = Pattern.compile("OfficeMarkdown-" + VERSION + "\\.msi");
    private static final int MAX_METADATA = 1_048_576;
    private static final int MAX_CHECKSUM = 4_096;
    private static final long MAX_INSTALLER = 512L * 1024 * 1024;
    private static final JsonPrimitive FALSE = new JsonPrimitive(false);

    static final class Release {
        private final String version;
        private final Asset installer;
        private final Asset checksum;

        private Release(String version, Asset installer, Asset checksum) {
            this.version = version;
            this.installer = installer;
            this.checksum = checksum;
        }

        String version() { return version; }
        URI page() { return URI.create(RELEASES + "tag/v" + version); }
    }

    private record Asset(String name, URI url, long size, byte[] digest) {}

    private record Version(int major, int minor, int patch) implements Comparable<Version> {
        private static Version parse(String value) throws IOException {
            var match = VERSION.matcher(value);
            if (!match.matches()) throw new IOException("업데이트 버전 번호 형식이 올바르지 않습니다.");
            return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)));
        }

        @Override public int compareTo(Version other) {
            int comparison = Integer.compare(major, other.major);
            if (comparison != 0) return comparison;
            comparison = Integer.compare(minor, other.minor);
            return comparison == 0 ? Integer.compare(patch, other.patch) : comparison;
        }
    }

    @FunctionalInterface
    interface Transport {
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> send(HttpRequest request);
    }

    private final Transport transport;
    private final Path tempRoot;
    private final Duration checkTimeout;
    private final Duration downloadTimeout;

    static ReleaseUpdate create() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new ReleaseUpdate(request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher()),
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(20), Duration.ofMinutes(5));
    }

    ReleaseUpdate(Transport transport, Path tempRoot, Duration checkTimeout, Duration downloadTimeout) {
        this.transport = transport;
        this.tempRoot = tempRoot.toAbsolutePath().normalize();
        this.checkTimeout = checkTimeout;
        this.downloadTimeout = downloadTimeout;
    }

    Optional<Release> check(String installedVersion) throws IOException, InterruptedException {
        var installed = Version.parse(installedVersion);
        var bytes = new ByteArrayOutputStream();
        transfer(LATEST, true, MAX_METADATA, -1, bytes, checkTimeout);
        JsonObject metadata;
        try {
            metadata = JSON.fromJson(bytes.toString(StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonParseException e) {
            throw invalidMetadata();
        }
        if (metadata == null || !FALSE.equals(metadata.get("draft")) || !FALSE.equals(metadata.get("prerelease"))) {
            throw invalidMetadata();
        }
        String tag = string(metadata, "tag_name");
        if (!tag.startsWith("v")) throw invalidMetadata();
        String version = tag.substring(1);
        if (Version.parse(version).compareTo(installed) <= 0) return Optional.empty();

        String name = "OfficeMarkdown-" + version + ".msi";
        Asset installer = asset(metadata, tag, name, MAX_INSTALLER);
        Asset checksum = asset(metadata, tag, name + ".sha256", MAX_CHECKSUM);
        return Optional.of(new Release(version, installer, checksum));
    }

    Path download(Release release) throws IOException, InterruptedException {
        var bytes = new ByteArrayOutputStream();
        var checksum = release.checksum;
        var installer = release.installer;
        transfer(checksum.url(), false, MAX_CHECKSUM, checksum.size(), bytes, checkTimeout);
        if (!MessageDigest.isEqual(sha256().digest(bytes.toByteArray()), checksum.digest())) {
            throw new IOException("다운로드한 체크섬 파일이 GitHub의 SHA-256과 일치하지 않습니다.");
        }
        var checksumLine = Pattern.compile("([0-9a-fA-F]{64}) {2}" + Pattern.quote(installer.name()) + "\\r?\\n?")
                .matcher(bytes.toString(StandardCharsets.US_ASCII));
        if (!checksumLine.matches() || !MessageDigest.isEqual(HexFormat.of().parseHex(checksumLine.group(1)), installer.digest())) {
            throw new IOException("체크섬 파일의 MSI 해시가 GitHub의 SHA-256과 일치하지 않습니다.");
        }

        Path directory = privateTempDirectory();
        Path file = directory.resolve(installer.name());
        try {
            var digest = sha256();
            try (var output = new DigestOutputStream(Files.newOutputStream(file, CREATE_NEW, WRITE), digest)) {
                transfer(installer.url(), false, MAX_INSTALLER, installer.size(), output, downloadTimeout);
            }
            if (!MessageDigest.isEqual(digest.digest(), installer.digest())) {
                throw new IOException("다운로드한 MSI가 배포본의 SHA-256과 일치하지 않습니다.");
            }
            if (Thread.interrupted()) throw new InterruptedException();
            return file;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try {
                discard(file);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    void verifyInstaller(Release release, Path installer) throws IOException, InterruptedException {
        Path file = validatedTempPath(installer);
        var expected = release.installer;
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!file.getFileName().toString().equals(expected.name()) || !attributes.isRegularFile()
                || attributes.size() != expected.size()) throw invalidLocalInstaller();

        var digest = sha256();
        long size = 0;
        try (var input = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
            byte[] bytes = new byte[32 * 1024];
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException();
                int read = input.read(bytes);
                if (read < 0) break;
                size += read;
                if (size > expected.size()) throw invalidLocalInstaller();
            }
        }
        if (size != expected.size() || !MessageDigest.isEqual(digest.digest(), expected.digest())) throw invalidLocalInstaller();
    }

    void discard(Path installer) throws IOException {
        Path file = validatedTempPath(installer);
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.getParent());
    }

    private Path validatedTempPath(Path installer) throws IOException {
        Path file = installer.toAbsolutePath().normalize();
        Path directory = file.getParent();
        if (directory == null || !tempRoot.equals(directory.getParent())
                || !TEMP_NAME.matcher(directory.getFileName().toString()).matches()
                || !INSTALLER_NAME.matcher(file.getFileName().toString()).matches()
                || Files.isSymbolicLink(directory)) {
            throw new IOException("업데이트용 임시 설치 파일 경로가 올바르지 않습니다.");
        }
        return file;
    }

    private static IOException invalidLocalInstaller() {
        return new IOException("임시 MSI가 검증한 배포본과 일치하지 않습니다.");
    }

    private Path privateTempDirectory() throws IOException {
        var fileSystem = tempRoot.getFileSystem();
        if (fileSystem.supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory(tempRoot, TEMP_PREFIX, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }
        var principals = fileSystem.getUserPrincipalLookupService();
        String domain = System.getenv("USERDOMAIN");
        String username = System.getProperty("user.name");
        var user = principals.lookupPrincipalByName(domain == null || domain.isBlank() ? username : domain + "\\" + username);
        var system = principals.lookupPrincipalByName("NT AUTHORITY\\SYSTEM");
        var acl = List.of(allowAccess(user), allowAccess(system));
        return Files.createTempDirectory(tempRoot, TEMP_PREFIX, new FileAttribute<List<AclEntry>>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return acl; }
        });
    }

    private static AclEntry allowAccess(UserPrincipal principal) {
        return AclEntry.newBuilder().setPrincipal(principal).setType(AclEntryType.ALLOW)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build();
    }

    private static Asset asset(JsonObject metadata, String tag, String name, long limit) throws IOException {
        var assets = metadata.get("assets");
        if (assets == null || !assets.isJsonArray()) throw invalidMetadata();
        Asset match = null;
        for (var item : assets.getAsJsonArray()) {
            if (!item.isJsonObject()) throw invalidMetadata();
            var candidate = item.getAsJsonObject();
            if (!new JsonPrimitive(name).equals(candidate.get("name"))) continue;
            if (match != null) throw invalidMetadata();
            URI url = URI.create(RELEASES + "download/" + tag + "/" + name);
            if (!url.toString().equals(string(candidate, "browser_download_url"))
                    || !"uploaded".equals(string(candidate, "state"))) throw invalidMetadata();
            long size = assetSize(candidate.get("size"));
            if (size < 1 || size > limit) throw invalidMetadata();
            String digest = string(candidate, "digest");
            if (!digest.startsWith("sha256:") || !HASH.matcher(digest.substring(7)).matches()) throw invalidMetadata();
            match = new Asset(name, url, size, HexFormat.of().parseHex(digest.substring(7)));
        }
        if (match == null) throw new IOException("GitHub 릴리스에서 업데이트 MSI와 체크섬 파일을 찾을 수 없습니다.");
        return match;
    }

    private static String string(JsonObject object, String key) throws IOException {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalidMetadata();
        return value.getAsString();
    }

    private static long assetSize(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalidMetadata();
        String size = value.getAsString();
        if (!size.matches("[0-9]{1,9}")) throw invalidMetadata();
        return Long.parseLong(size);
    }

    private static IOException invalidMetadata() {
        return new IOException("GitHub 업데이트 릴리스 정보 형식이 올바르지 않습니다.");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void transfer(URI start, boolean metadata, long limit, long expected, OutputStream output, Duration timeout)
            throws IOException, InterruptedException {
        var deadline = new Deadline(System.nanoTime() + timeout.toNanos());
        URI url = start;
        while (true) {
            String accept = metadata ? "application/vnd.github+json" : "application/octet-stream";
            var request = HttpRequest.newBuilder(url).header("User-Agent", "OfficeMarkdown")
                    .header("Accept", accept).timeout(Duration.ofNanos(deadline.remaining())).build();
            var pending = transport.send(request);
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> response;
            try {
                response = deadline.await(pending);
            } catch (HttpTimeoutException | InterruptedException e) {
                if (!pending.cancel(true)) pending.thenAccept(late -> new BodyReader(late.body()).close());
                throw e;
            }
            try (var body = new BodyReader(response.body())) {
                if (response.statusCode() == 302 && !metadata && url.equals(start)) {
                    url = cdnLocation(response);
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("GitHub 업데이트 요청에 실패했습니다 (HTTP " + response.statusCode() + ").");
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > limit || (declared >= 0 && expected >= 0 && declared != expected)) throw invalidSize();
                long received = body.copy(output, expected < 0 ? limit : expected, deadline);
                if ((declared >= 0 && received != declared) || (expected >= 0 && received != expected)) throw invalidSize();
                return;
            } catch (NumberFormatException e) {
                throw invalidSize();
            }
        }
    }

    private static URI cdnLocation(HttpResponse<?> response) throws IOException {
        var locations = response.headers().allValues("Location");
        if (locations.size() != 1 || locations.getFirst().length() > 8192) throw invalidRedirect();
        URI url;
        try {
            url = URI.create(locations.getFirst());
        } catch (IllegalArgumentException e) {
            throw invalidRedirect();
        }
        if (!"https".equals(url.getScheme()) || !"release-assets.githubusercontent.com".equals(url.getRawAuthority())
                || url.getRawFragment() != null || !CDN_PATH.matcher(url.getRawPath()).matches()) throw invalidRedirect();
        return url;
    }

    private static IOException invalidRedirect() {
        return new IOException("GitHub 업데이트 다운로드 주소를 확인할 수 없습니다.");
    }

    private static IOException invalidSize() {
        return new IOException("GitHub 업데이트 응답 크기가 올바르지 않습니다.");
    }

    private static HttpTimeoutException timedOut() {
        return new HttpTimeoutException("GitHub 업데이트 응답 시간이 초과되었습니다.");
    }

    private record Deadline(long end) {
        long remaining() throws HttpTimeoutException {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) throw timedOut();
            return remaining;
        }

        <T> T await(CompletableFuture<T> future) throws IOException, InterruptedException {
            try {
                return future.get(remaining(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw timedOut();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof HttpTimeoutException) throw timedOut();
                throw new IOException("GitHub에 연결할 수 없습니다.");
            }
        }
    }

    private static final class BodyReader implements Flow.Subscriber<List<ByteBuffer>>, AutoCloseable {
        private record Chunk(List<ByteBuffer> bytes, boolean failed) {}

        // One requested batch and its terminal notification can arrive before the reader wakes up.
        private final ArrayBlockingQueue<Chunk> chunks = new ArrayBlockingQueue<>(2);
        private final CompletableFuture<Flow.Subscription> subscribed = new CompletableFuture<>();

        BodyReader(Flow.Publisher<List<ByteBuffer>> publisher) { publisher.subscribe(this); }

        @Override public void onSubscribe(Flow.Subscription subscription) { subscribed.complete(subscription); }
        @Override public void onNext(List<ByteBuffer> item) { chunks.add(new Chunk(item, false)); }
        @Override public void onError(Throwable error) { chunks.add(new Chunk(null, true)); }
        @Override public void onComplete() { chunks.add(new Chunk(null, false)); }

        @Override public void close() { subscribed.thenAccept(Flow.Subscription::cancel); }

        long copy(OutputStream output, long limit, Deadline deadline) throws IOException, InterruptedException {
            // The subscription must stay usable for cancellation if the deadline expires during onSubscribe.
            Flow.Subscription subscription;
            try {
                subscription = subscribed.get(deadline.remaining(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw timedOut();
            } catch (ExecutionException e) {
                throw new IOException("GitHub 업데이트 응답을 열 수 없습니다.");
            }
            long total = 0;
            byte[] buffer = new byte[32 * 1024];
            while (true) {
                subscription.request(1);
                Chunk chunk = chunks.poll(deadline.remaining(), TimeUnit.NANOSECONDS);
                if (chunk == null) throw timedOut();
                if (chunk.failed) throw new IOException("GitHub 업데이트 응답이 중단되었습니다.");
                if (chunk.bytes == null) return total;
                for (var bytes : chunk.bytes) {
                    if (bytes.remaining() > limit - total) throw invalidSize();
                    total += bytes.remaining();
                    while (bytes.hasRemaining()) {
                        int count = Math.min(buffer.length, bytes.remaining());
                        bytes.get(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }
}
