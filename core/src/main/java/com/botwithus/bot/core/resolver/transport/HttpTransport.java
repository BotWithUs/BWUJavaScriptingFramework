package com.botwithus.bot.core.resolver.transport;

import com.botwithus.bot.core.resolver.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP(S) transport built on {@link HttpClient}. Streams the response
 * body into a sibling {@code .part} file then atomically renames into
 * place on success — destination is never observed half-written.
 *
 * <p>Basic-auth is applied when {@link Credentials} is present. The
 * username:password tuple is encoded as UTF-8 per RFC 7617 before
 * base64; over HTTPS this is safe, over plain HTTP it is on-the-wire
 * readable — the CLI {@code repo add} warns the user when adding an
 * {@code http://} repo.</p>
 *
 * <p>Constructor-injected timeouts and follow-redirects policy so tests
 * can dial them in.</p>
 */
public final class HttpTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String AUTH_HEADER = "Authorization";
    private static final String AUTH_SCHEME_BASIC = "Basic ";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String USER_AGENT_VALUE = "botwithus-resolver/1";
    private static final String PART_SUFFIX = ".part";
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;

    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpTransport() {
        this(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .build(),
                DEFAULT_REQUEST_TIMEOUT);
    }

    public HttpTransport(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public CompletableFuture<TransportResult> fetch(URI source, Path destination, Optional<Credentials> credentials) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(credentials, "credentials");

        Path parent = destination.getParent();
        Path partFile;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            partFile = (parent != null ? parent : Path.of("."))
                    .resolve(destination.getFileName() + PART_SUFFIX);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(new TransportResult.Network(source.toString(), e));
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(source)
                .timeout(requestTimeout)
                .header(USER_AGENT_HEADER, USER_AGENT_VALUE)
                .GET();
        credentials.ifPresent(c -> reqBuilder.header(AUTH_HEADER, basicAuth(c)));

        HttpRequest request = reqBuilder.build();
        return client.sendAsync(request, BodyHandlers.ofFile(partFile))
                .handle((response, throwable) -> finalize(source, destination, partFile, response, throwable));
    }

    private TransportResult finalize(URI source, Path destination, Path partFile,
                                     HttpResponse<Path> response, Throwable throwable) {
        if (throwable != null) {
            cleanup(partFile);
            return new TransportResult.Network(source.toString(), new IOException(throwable));
        }

        int status = response.statusCode();
        if (status == HTTP_NOT_FOUND) {
            cleanup(partFile);
            return new TransportResult.NotFound(source.toString());
        }
        if (status != HTTP_OK) {
            cleanup(partFile);
            return new TransportResult.HttpError(source.toString(), status, "HTTP " + status);
        }

        try {
            long bytes = Files.size(partFile);
            renameWithAtomicFallback(partFile, destination);
            return new TransportResult.Ok(destination, bytes);
        } catch (IOException e) {
            cleanup(partFile);
            return new TransportResult.Network(source.toString(), e);
        }
    }

    private static void cleanup(Path partFile) {
        try {
            Files.deleteIfExists(partFile);
        } catch (IOException e) {
            log.debug("failed to clean up partial file {}", partFile, e);
        }
    }

    private static void renameWithAtomicFallback(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicFailed) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String basicAuth(Credentials c) {
        String pair = c.username() + ":" + c.password();
        return AUTH_SCHEME_BASIC + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
