package com.botwithus.bot.core.loader.bootstrap;

import com.botwithus.bot.core.loader.bootstrap.NativeArtifactManifest.Entry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeArtifactDownloaderTest {

    private static final byte[] DLL_BYTES = "fake-dll-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private NativeArtifactDownloader downloader;
    private NativeCache cache;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cache = new NativeCache(tempDir.resolve("cache"));
        downloader = new NativeArtifactDownloader(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(10),
                cache);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void stubEntryIsSkippedWithoutNetworkCall() {
        // Server never started — any HTTP attempt would fail.
        Entry.Dll stubbed = new Entry.Dll(
                "bwu.dll",
                URI.create("https://" + NativeArtifactManifest.STUB_HOST + "/bwu.dll"),
                NativeArtifactManifest.STUB_DIGEST);

        NativeArtifactDownloader.Result result = downloader.download(stubbed);

        NativeArtifactDownloader.Result.Skipped skipped =
                assertInstanceOf(NativeArtifactDownloader.Result.Skipped.class, result);
        assertEquals(stubbed, skipped.entry());
        assertFalse(Files.exists(cache.resolve("bwu.dll")));
    }

    @Test
    void dllDownloadsAndPlacesIntoCache() throws IOException {
        server.createContext("/bwu.dll", ex -> respond(ex, 200, DLL_BYTES));
        server.start();

        Entry.Dll entry = new Entry.Dll("bwu.dll", baseUri().resolve("bwu.dll"), sha256Hex(DLL_BYTES));

        NativeArtifactDownloader.Result result = downloader.download(entry);

        NativeArtifactDownloader.Result.Downloaded ok =
                assertInstanceOf(NativeArtifactDownloader.Result.Downloaded.class, result);
        Path placed = cache.resolve("bwu.dll");
        assertEquals(placed, ok.location());
        assertEquals(DLL_BYTES.length, ok.bytesWritten());
        assertArrayEquals(DLL_BYTES, Files.readAllBytes(placed));
        assertFalse(Files.exists(cache.resolve("bwu.dll.part")));
    }

    @Test
    void secondRunReportsAlreadyCached() throws IOException {
        server.createContext("/bwu.dll", ex -> respond(ex, 200, DLL_BYTES));
        server.start();

        Entry.Dll entry = new Entry.Dll("bwu.dll", baseUri().resolve("bwu.dll"), sha256Hex(DLL_BYTES));
        downloader.download(entry);

        NativeArtifactDownloader.Result second = downloader.download(entry);

        assertInstanceOf(NativeArtifactDownloader.Result.AlreadyCached.class, second);
    }

    @Test
    void checksumMismatchDeletesPartAndRejects() throws IOException {
        server.createContext("/bwu.dll", ex -> respond(ex, 200, DLL_BYTES));
        server.start();

        String wrongDigest = sha256Hex("different-bytes".getBytes(StandardCharsets.UTF_8));
        Entry.Dll entry = new Entry.Dll("bwu.dll", baseUri().resolve("bwu.dll"), wrongDigest);

        NativeArtifactDownloader.Result result = downloader.download(entry);

        NativeArtifactDownloader.Result.ChecksumMismatch mismatch =
                assertInstanceOf(NativeArtifactDownloader.Result.ChecksumMismatch.class, result);
        assertEquals(wrongDigest, mismatch.expected());
        assertFalse(Files.exists(cache.resolve("bwu.dll")));
        assertFalse(Files.exists(cache.resolve("bwu.dll.part")));
    }

    @Test
    void httpErrorSurfacesAsNetworkFailure() throws IOException {
        server.createContext("/bwu.dll", ex -> respond(ex, 500, new byte[0]));
        server.start();

        Entry.Dll entry = new Entry.Dll("bwu.dll", baseUri().resolve("bwu.dll"), sha256Hex(DLL_BYTES));

        NativeArtifactDownloader.Result result = downloader.download(entry);

        NativeArtifactDownloader.Result.NetworkFailure failure =
                assertInstanceOf(NativeArtifactDownloader.Result.NetworkFailure.class, result);
        assertTrue(failure.message().contains("500"));
        assertFalse(Files.exists(cache.resolve("bwu.dll")));
    }

    @Test
    void zipExtractsContentsAndWritesMarker() throws IOException {
        byte[] zipBytes = buildZip(List.of(
                new ZipMember("NXTCache.dll", "nxt-payload".getBytes(StandardCharsets.UTF_8)),
                new ZipMember("subdir/helper.dll", "helper-payload".getBytes(StandardCharsets.UTF_8))));
        server.createContext("/libs.zip", ex -> respond(ex, 200, zipBytes));
        server.start();

        Entry.Zip entry = new Entry.Zip("libs.zip", baseUri().resolve("libs.zip"), sha256Hex(zipBytes));

        NativeArtifactDownloader.Result result = downloader.download(entry);

        assertInstanceOf(NativeArtifactDownloader.Result.Downloaded.class, result);
        assertTrue(Files.exists(cache.resolve("NXTCache.dll")));
        assertTrue(Files.exists(cache.resolve("subdir/helper.dll")));
        assertFalse(Files.exists(cache.resolve("libs.zip")), "staging archive should be deleted");
        assertFalse(Files.exists(cache.resolve("libs.zip.part")), "no .part should remain");
        assertTrue(Files.exists(cache.resolve("libs.zip.installed")), "marker should record install");
    }

    @Test
    void zipSecondRunReportsAlreadyCached() throws IOException {
        byte[] zipBytes = buildZip(List.of(
                new ZipMember("NXTCache.dll", "payload".getBytes(StandardCharsets.UTF_8))));
        server.createContext("/libs.zip", ex -> respond(ex, 200, zipBytes));
        server.start();

        Entry.Zip entry = new Entry.Zip("libs.zip", baseUri().resolve("libs.zip"), sha256Hex(zipBytes));
        downloader.download(entry);

        NativeArtifactDownloader.Result second = downloader.download(entry);

        assertInstanceOf(NativeArtifactDownloader.Result.AlreadyCached.class, second);
    }

    @Test
    void zipSlipPathIsRejected() throws IOException {
        byte[] zipBytes = buildZip(List.of(
                new ZipMember("../escape.dll", "malicious".getBytes(StandardCharsets.UTF_8))));
        server.createContext("/evil.zip", ex -> respond(ex, 200, zipBytes));
        server.start();

        Entry.Zip entry = new Entry.Zip("evil.zip", baseUri().resolve("evil.zip"), sha256Hex(zipBytes));

        NativeArtifactDownloader.Result result = downloader.download(entry);

        assertInstanceOf(NativeArtifactDownloader.Result.IoFailure.class, result);
        // The cache root itself must not contain an escapee under any name.
        assertFalse(Files.exists(cache.cacheDir().getParent().resolve("escape.dll")));
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } else {
            ex.close();
        }
    }

    private record ZipMember(String name, byte[] content) {}

    private static byte[] buildZip(List<ZipMember> members) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
            for (ZipMember m : members) {
                zos.putNextEntry(new ZipEntry(m.name()));
                zos.write(m.content());
                zos.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
