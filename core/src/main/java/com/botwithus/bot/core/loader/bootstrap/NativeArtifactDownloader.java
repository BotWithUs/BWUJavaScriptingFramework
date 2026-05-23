package com.botwithus.bot.core.loader.bootstrap;

import com.botwithus.bot.core.loader.bootstrap.NativeArtifactManifest.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetches {@link NativeArtifactManifest} entries into a {@link NativeCache}.
 *
 * <p>Each entry runs through the same pipeline:
 * <ol>
 *   <li><b>Stub guard</b> — entries whose URL host equals
 *       {@link NativeArtifactManifest#STUB_HOST} or whose digest equals
 *       {@link NativeArtifactManifest#STUB_DIGEST} return
 *       {@link Result.Skipped} without touching the network. This lets the
 *       bootstrap ship before real hosting is wired up.</li>
 *   <li><b>Cache check</b> — a sibling marker file
 *       ({@code <name>.installed}) records the digest of the archive last
 *       extracted. A match returns {@link Result.AlreadyCached} without
 *       any HTTP request.</li>
 *   <li><b>Fetch</b> — bytes stream into a sibling {@code .part} file.
 *       The cache is observed in only two states: empty (or stale), or
 *       fully written and digest-verified.</li>
 *   <li><b>Verify</b> — SHA-256 of the {@code .part} file is compared to
 *       the manifest digest. Mismatch deletes the partial file and returns
 *       {@link Result.ChecksumMismatch}.</li>
 *   <li><b>Place</b> — the staged archive is extracted (with a zip-slip
 *       guard) into the cache, deleted, and the marker file is written.</li>
 * </ol>
 *
 * <p>All failures are returned as {@link Result} values; no exception
 * leaks across the {@link #download(Entry)} boundary.</p>
 */
public final class NativeArtifactDownloader {

    private static final Logger log = LoggerFactory.getLogger(NativeArtifactDownloader.class);

    private static final String SHA_256 = "SHA-256";
    private static final String PART_SUFFIX = ".part";
    private static final String MARKER_SUFFIX = ".installed";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String USER_AGENT_VALUE = "botwithus-bootstrap/1";
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int DIGEST_BUFFER_BYTES = 64 * 1024;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient client;
    private final Duration requestTimeout;
    private final NativeCache cache;

    /** Defaults: 15s connect timeout, 5min request timeout, {@code ~/.botwithus/native/} cache. */
    public NativeArtifactDownloader() {
        this(defaultClient(), DEFAULT_REQUEST_TIMEOUT, new NativeCache());
    }

    public NativeArtifactDownloader(HttpClient client, Duration requestTimeout, NativeCache cache) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
    }

    /** Run every entry in {@code manifest} through {@link #download(Entry)} in declaration order. */
    public void downloadAll(NativeArtifactManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        for (Entry entry : manifest.entries()) {
            Result result = download(entry);
            logResult(result);
        }
    }

    /** Fetch one entry. Never throws — every failure mode is a {@link Result} variant. */
    public Result download(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (isStub(entry)) {
            return new Result.Skipped(entry, "URL not configured");
        }
        try {
            Path marker = cache.resolve(entry.destFilename() + MARKER_SUFFIX);
            if (markerMatches(marker, entry.sha256Hex())) {
                return new Result.AlreadyCached(entry, cache.cacheDir());
            }
            cache.ensureExists();
            Path stagedZip = cache.resolve(entry.destFilename() + PART_SUFFIX);
            FetchOutcome fetched = fetchToPart(entry.url(), stagedZip);
            Result failure = toFailure(entry, fetched);
            if (failure != null) {
                return failure;
            }
            String actual = hex(sha256(stagedZip));
            if (!actual.equalsIgnoreCase(entry.sha256Hex())) {
                Files.deleteIfExists(stagedZip);
                return new Result.ChecksumMismatch(entry, entry.sha256Hex(), actual);
            }
            long bytes = Files.size(stagedZip);
            extractZip(stagedZip, cache.cacheDir());
            Files.deleteIfExists(stagedZip);
            writeMarker(marker, entry.sha256Hex());
            return new Result.Downloaded(entry, cache.cacheDir(), bytes);
        } catch (IOException e) {
            return new Result.IoFailure(entry, describe(e));
        }
    }

    private FetchOutcome fetchToPart(URI source, Path partFile) {
        HttpRequest request = HttpRequest.newBuilder(source)
                .timeout(requestTimeout)
                .header(USER_AGENT_HEADER, USER_AGENT_VALUE)
                .GET()
                .build();
        try {
            HttpResponse<Path> response = client.send(request, BodyHandlers.ofFile(partFile));
            int status = response.statusCode();
            if (status == HTTP_OK) {
                return new FetchOutcome.Ok(Files.size(partFile));
            }
            deleteQuiet(partFile);
            if (status == HTTP_NOT_FOUND) {
                return new FetchOutcome.NotFound();
            }
            return new FetchOutcome.HttpStatus(status);
        } catch (IOException e) {
            deleteQuiet(partFile);
            return new FetchOutcome.Network(describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuiet(partFile);
            return new FetchOutcome.Network("interrupted");
        }
    }

    private static Result toFailure(Entry entry, FetchOutcome outcome) {
        return switch (outcome) {
            case FetchOutcome.Ok ok -> null;
            case FetchOutcome.NotFound nf -> new Result.NetworkFailure(entry, "HTTP 404");
            case FetchOutcome.HttpStatus s -> new Result.NetworkFailure(entry, "HTTP " + s.code());
            case FetchOutcome.Network n -> new Result.NetworkFailure(entry, n.message());
        };
    }

    private static void extractZip(Path zipPath, Path destDir) throws IOException {
        Path normalizedDest = destDir.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(zipPath);
             BufferedInputStream buffered = new BufferedInputStream(raw);
             ZipInputStream zis = new ZipInputStream(buffered)) {
            ZipEntry zEntry;
            while ((zEntry = zis.getNextEntry()) != null) {
                Path resolved = destDir.resolve(zEntry.getName()).toAbsolutePath().normalize();
                if (!resolved.startsWith(normalizedDest)) {
                    throw new IOException("zip entry escapes destination: " + zEntry.getName());
                }
                if (zEntry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private boolean markerMatches(Path marker, String expectedHex) {
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        try {
            String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
            return recorded.equalsIgnoreCase(expectedHex);
        } catch (IOException e) {
            log.debug("marker read failed for {}: {}", marker, describe(e));
            return false;
        }
    }

    private static void writeMarker(Path marker, String digestHex) throws IOException {
        Files.writeString(marker, digestHex, StandardCharsets.UTF_8);
    }

    private static byte[] sha256(Path path) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream raw = Files.newInputStream(path);
             BufferedInputStream in = new BufferedInputStream(raw)) {
            byte[] buf = new byte[DIGEST_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable in this JVM", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static void deleteQuiet(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("failed to clean up {}: {}", path, describe(e));
        }
    }

    private static boolean isStub(Entry entry) {
        String host = entry.url().getHost();
        return (host != null && host.equalsIgnoreCase(NativeArtifactManifest.STUB_HOST))
                || NativeArtifactManifest.STUB_DIGEST.equals(entry.sha256Hex());
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message;
    }

    private static void logResult(Result result) {
        String name = result.entry().destFilename();
        switch (result) {
            case Result.Skipped s -> log.info("Native bootstrap: {} skipped ({})", name, s.reason());
            case Result.AlreadyCached c -> log.info("Native bootstrap: {} already present at {}", name, c.location());
            case Result.Downloaded d -> log.info("Native bootstrap: {} fetched ({} bytes) into {}", name, d.bytesWritten(), d.location());
            case Result.ChecksumMismatch m -> log.warn("Native bootstrap: {} checksum mismatch — expected {}, actual {}", name, m.expected(), m.actual());
            case Result.NetworkFailure n -> log.warn("Native bootstrap: {} network failure: {}", name, n.message());
            case Result.IoFailure i -> log.warn("Native bootstrap: {} I/O failure: {}", name, i.message());
        }
    }

    private sealed interface FetchOutcome
            permits FetchOutcome.Ok,
                    FetchOutcome.NotFound,
                    FetchOutcome.HttpStatus,
                    FetchOutcome.Network {
        record Ok(long bytes) implements FetchOutcome {}
        record NotFound() implements FetchOutcome {}
        record HttpStatus(int code) implements FetchOutcome {}
        record Network(String message) implements FetchOutcome {}
    }

    /** Outcome of a single {@link #download(Entry)} call. */
    public sealed interface Result
            permits Result.AlreadyCached,
                    Result.Downloaded,
                    Result.Skipped,
                    Result.ChecksumMismatch,
                    Result.NetworkFailure,
                    Result.IoFailure {
        Entry entry();

        record AlreadyCached(Entry entry, Path location) implements Result {}
        record Downloaded(Entry entry, Path location, long bytesWritten) implements Result {}
        record Skipped(Entry entry, String reason) implements Result {}
        record ChecksumMismatch(Entry entry, String expected, String actual) implements Result {}
        record NetworkFailure(Entry entry, String message) implements Result {}
        record IoFailure(Entry entry, String message) implements Result {}
    }
}
