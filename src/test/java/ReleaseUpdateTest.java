import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseUpdateTest {
    private static final String LATEST = "https://api.github.com/repos/dlgorbs12-rrr/OfficeMarkdown/releases/latest";
    private static final String RELEASES = "https://github.com/dlgorbs12-rrr/OfficeMarkdown/releases/";
    private static final String TAG = "v2.6.10";
    private static final String NAME = "OfficeMarkdown-2.6.10.msi";
    private static final String INSTALLER_URL = RELEASES + "download/" + TAG + "/" + NAME;
    private static final String CDN = "https://release-assets.githubusercontent.com/github-production-release-asset/1/"
            + "92abd363-ad70-417f-a7b2-81d340d0e918?signature=test";
    private static final byte[] INSTALLER = "test installer payload".getBytes(UTF_8);
    private static final byte[] CHECKSUM = (hash(INSTALLER) + "  " + NAME + "\r\n").getBytes(UTF_8);
    private static final Duration SHORT_WAIT = Duration.ofMillis(250);

    @TempDir Path temp;

    @Test void checksOnlyLatestStableAndComparesVersionPartsNumerically() throws Exception {
        var requests = new ArrayList<HttpRequest>();
        var service = service(request -> {
            requests.add(request);
            return response(request, metadata(CHECKSUM));
        });
        var release = service.check("2.6.9").orElseThrow();
        assertEquals("2.6.10", release.version());
        assertEquals(URI.create(RELEASES + "tag/" + TAG), release.page());
        assertTrue(service.check("2.6.10").isEmpty());
        assertTrue(service.check("2.10.0").isEmpty());
        assertThrows(IOException.class, () -> service.check("2.6"));
        assertEquals(3, requests.size());
        for (var request : requests) {
            assertEquals(URI.create(LATEST), request.uri());
            assertEquals("GET", request.method());
            assertEquals("application/vnd.github+json", request.headers().firstValue("Accept").orElseThrow());
        }
    }

    @Test void rejectsPrereleaseAndUntrustedOrMissingAssetMetadata() throws Exception {
        for (String field : List.of("draft", "prerelease")) {
            var data = metadata(CHECKSUM);
            data.addProperty(field, true);
            assertThrows(IOException.class, () -> service(request -> response(request, data)).check("2.6.1"));
        }
        for (String field : List.of("digest", "size", "state", "browser_download_url")) {
            var data = metadata(CHECKSUM);
            data.getAsJsonArray("assets").get(0).getAsJsonObject().remove(field);
            assertThrows(IOException.class, () -> service(request -> response(request, data)).check("2.6.1"));
        }
        var data = metadata(CHECKSUM);
        var assets = data.getAsJsonArray("assets");
        var installer = assets.get(1).getAsJsonObject();
        installer.addProperty("browser_download_url", INSTALLER_URL + "?other=asset");
        assertThrows(IOException.class, () -> service(request -> response(request, data)).check("2.6.1"));
        installer.addProperty("browser_download_url", INSTALLER_URL);
        assets.add(installer);
        assertThrows(IOException.class, () -> service(request -> response(request, data)).check("2.6.1"));
    }

    @Test void downloadsThroughTheGitHubCdnAndKeepsEachInstallerSeparate() throws Exception {
        var requests = new ArrayList<URI>();
        var service = service(request -> {
            requests.add(request.uri());
            return switch (request.uri().toString()) {
                case LATEST -> response(request, metadata(CHECKSUM));
                case INSTALLER_URL + ".sha256" -> response(request, CHECKSUM);
                case INSTALLER_URL -> new Response(request, 302, headers("Location", CDN), body(new byte[0]));
                case CDN -> response(request, INSTALLER);
                default -> throw new AssertionError(request.uri());
            };
        });
        var release = service.check("2.6.1").orElseThrow();
        var first = service.download(release);
        var second = service.download(release);
        assertEquals(temp, first.getParent().getParent());
        assertEquals(NAME, first.getFileName().toString());
        assertNotEquals(first.getParent(), second.getParent());
        assertArrayEquals(INSTALLER, Files.readAllBytes(first));
        service.verifyInstaller(release, first);
        byte[] changed = INSTALLER.clone();
        changed[0] ^= 1;
        Files.write(first, changed);
        assertThrows(IOException.class, () -> service.verifyInstaller(release, first));
        service.discard(first);
        assertFalse(Files.exists(first.getParent()));
        assertArrayEquals(INSTALLER, Files.readAllBytes(second));
        service.discard(second);
        assertTempEmpty();
        assertEquals(List.of(LATEST, INSTALLER_URL + ".sha256", INSTALLER_URL, CDN, INSTALLER_URL + ".sha256",
                INSTALLER_URL, CDN).stream().map(URI::create).toList(), requests);
    }

    @Test void rejectsUntrustedRedirectsWithoutRequestingThem() throws Exception {
        for (var target : List.of(CDN.replace("https:", "http:"), CDN.replace(".com/", ".com.example/"),
                CDN.replace(".com/", ".com@elsewhere.example/"), CDN.replace("/github-production-release-asset/", "/elsewhere/"))) {
            var service = service(request -> switch (request.uri().toString()) {
                case LATEST -> response(request, metadata(CHECKSUM));
                case INSTALLER_URL + ".sha256" -> new Response(request, 302, headers("Location", target), body(new byte[0]));
                default -> throw new AssertionError(request.uri());
            });
            var release = service.check("2.6.1").orElseThrow();
            assertThrows(IOException.class, () -> service.download(release));
        }
        assertTempEmpty();
    }

    @Test void rejectsBothKindsOfChecksumMismatchBeforeRequestingTheInstaller() throws Exception {
        byte[] wrongHash = ("0".repeat(64) + "  " + NAME + "\r\n").getBytes(UTF_8);
        for (var expectedChecksum : List.of(CHECKSUM, wrongHash)) {
            var service = service(request -> switch (request.uri().toString()) {
                case LATEST -> response(request, metadata(expectedChecksum));
                case INSTALLER_URL + ".sha256" -> response(request, wrongHash);
                default -> throw new AssertionError(request.uri());
            });
            var release = service.check("2.6.1").orElseThrow();
            assertThrows(IOException.class, () -> service.download(release));
        }
        assertTempEmpty();
    }

    @Test void discardsOversizedTruncatedAndCorruptInstallers() throws Exception {
        for (int delta : List.of(-1, 0, 1)) {
            byte[] wrong = Arrays.copyOf(INSTALLER, INSTALLER.length + delta);
            wrong[0] ^= 1;
            var service = service(request -> switch (request.uri().toString()) {
                case LATEST -> response(request, metadata(CHECKSUM));
                case INSTALLER_URL + ".sha256" -> response(request, CHECKSUM);
                case INSTALLER_URL -> new Response(request, 200, headers(), body(wrong));
                default -> throw new AssertionError(request.uri());
            });
            var release = service.check("2.6.1").orElseThrow();
            assertThrows(IOException.class, () -> service.download(release));
            assertTempEmpty();
        }
    }

    @Test void boundsMetadataEvenWithoutContentLength() {
        var bytes = new byte[1_048_577];
        var service = service(request -> new Response(request, 200, headers(), body(bytes)));
        assertThrows(IOException.class, () -> service.check("2.6.1"));
    }

    @Test void timesOutWaitingForHeadersAndCancelsTheRequest() {
        var pending = new CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>>();
        var service = new ReleaseUpdate(request -> pending, temp, SHORT_WAIT, SHORT_WAIT);
        assertTimeout(Duration.ofSeconds(3), () -> assertThrows(HttpTimeoutException.class, () -> service.check("2.6.1")));
        assertTrue(pending.isCancelled());
    }

    @Test void timesOutDuringTheInstallerBodyAndDiscardsThePartialFile() throws Exception {
        var requested = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var service = stalledInstaller(requested, cancelled, SHORT_WAIT);
        var release = service.check("2.6.1").orElseThrow();
        assertTimeout(Duration.ofSeconds(3), () -> assertThrows(HttpTimeoutException.class, () -> service.download(release)));
        assertEquals(0, requested.getCount());
        assertTrue(cancelled.get());
        assertTempEmpty();
    }

    @Test void interruptionCancelsTheDownloadAndDiscardsThePartialFile() throws Exception {
        var requested = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var service = stalledInstaller(requested, cancelled, Duration.ofSeconds(10));
        var release = service.check("2.6.1").orElseThrow();
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofVirtual().start(() -> {
            try {
                service.download(release);
            } catch (Exception e) {
                failure.set(e);
            }
        });
        assertTrue(requested.await(5, TimeUnit.SECONDS));
        worker.interrupt();
        assertTrue(worker.join(Duration.ofSeconds(5)));
        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(cancelled.get());
        assertTempEmpty();
    }

    private ReleaseUpdate stalledInstaller(CountDownLatch requested, AtomicBoolean cancelled, Duration downloadTimeout) {
        return new ReleaseUpdate(request -> CompletableFuture.completedFuture(switch (request.uri().toString()) {
            case LATEST -> response(request, metadata(CHECKSUM));
            case INSTALLER_URL + ".sha256" -> response(request, CHECKSUM);
            case INSTALLER_URL -> new Response(request, 200, headers(), subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) {
                    if (requested.getCount() == 0) return;
                    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{INSTALLER[0]})));
                    requested.countDown();
                }
                @Override public void cancel() { cancelled.set(true); }
            }));
            default -> throw new AssertionError(request.uri());
        }), temp, Duration.ofSeconds(5), downloadTimeout);
    }

    private ReleaseUpdate service(Function<HttpRequest, Response> respond) {
        return new ReleaseUpdate(request -> CompletableFuture.completedFuture(respond.apply(request)), temp,
                Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private void assertTempEmpty() throws IOException {
        try (var paths = Files.list(temp)) {
            assertEquals(0, paths.count());
        }
    }

    private static JsonObject metadata(byte[] checksum) {
        var data = new JsonObject();
        data.addProperty("draft", false);
        data.addProperty("prerelease", false);
        data.addProperty("tag_name", TAG);
        var assets = new JsonArray();
        assets.add(asset(NAME + ".sha256", checksum));
        assets.add(asset(NAME, INSTALLER));
        data.add("assets", assets);
        return data;
    }

    private static JsonObject asset(String name, byte[] bytes) {
        var asset = new JsonObject();
        asset.addProperty("name", name);
        asset.addProperty("browser_download_url", RELEASES + "download/" + TAG + "/" + name);
        asset.addProperty("state", "uploaded");
        asset.addProperty("size", bytes.length);
        asset.addProperty("digest", "sha256:" + hash(bytes));
        return asset;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static Response response(HttpRequest request, JsonObject data) {
        return response(request, data.toString().getBytes(UTF_8));
    }

    private static Response response(HttpRequest request, byte[] bytes) {
        return new Response(request, 200, headers("Content-Length", String.valueOf(bytes.length)), body(bytes));
    }

    private static HttpHeaders headers() { return HttpHeaders.of(Map.of(), (key, value) -> true); }

    private static HttpHeaders headers(String key, String value) {
        return HttpHeaders.of(Map.of(key, List.of(value)), (name, datum) -> true);
    }

    private static Flow.Publisher<List<ByteBuffer>> body(byte[] bytes) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;
            @Override public void request(long count) {
                if (done) return;
                done = true;
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                subscriber.onComplete();
            }
            @Override public void cancel() { done = true; }
        });
    }

    private record Response(HttpRequest request, int statusCode, HttpHeaders headers, Flow.Publisher<List<ByteBuffer>> body)
            implements HttpResponse<Flow.Publisher<List<ByteBuffer>>> {
        @Override public Optional<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
