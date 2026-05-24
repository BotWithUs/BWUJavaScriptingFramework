package com.botwithus.bot.core.resolver.transport;

import com.botwithus.bot.core.resolver.Credentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HttpTransportTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private HttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        transport = new HttpTransport(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(10));
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
    void downloadsArtifactSuccessfully() throws IOException {
        byte[] body = "hello-world".getBytes(StandardCharsets.UTF_8);
        server.createContext("/com/example/art-1.0.jar", ex -> respond(ex, 200, body));
        server.start();

        Path dest = tempDir.resolve("out").resolve("art-1.0.jar");
        TransportResult result = transport.fetch(
                baseUri().resolve("com/example/art-1.0.jar"),
                dest,
                Optional.empty()).join();

        TransportResult.Ok ok = assertInstanceOf(TransportResult.Ok.class, result);
        assertEquals(dest, ok.localPath());
        assertEquals(body.length, ok.bytesWritten());
        assertArrayEquals(body, Files.readAllBytes(dest));
    }

    @Test
    void notFoundResolvesToNotFound() throws IOException {
        server.createContext("/", ex -> respond(ex, 404, new byte[0]));
        server.start();

        Path dest = tempDir.resolve("missing.jar");
        TransportResult result = transport.fetch(
                baseUri().resolve("does-not-exist.jar"), dest, Optional.empty()).join();

        assertInstanceOf(TransportResult.NotFound.class, result);
        assertFalse(Files.exists(dest));
    }

    @Test
    void serverErrorResolvesToHttpError() throws IOException {
        server.createContext("/", ex -> respond(ex, 500, "server fault".getBytes(StandardCharsets.UTF_8)));
        server.start();

        Path dest = tempDir.resolve("boom.jar");
        TransportResult result = transport.fetch(baseUri().resolve("boom.jar"), dest, Optional.empty()).join();

        TransportResult.HttpError err = assertInstanceOf(TransportResult.HttpError.class, result);
        assertEquals(500, err.statusCode());
        assertFalse(Files.exists(dest));
    }

    @Test
    void basicAuthIsApplied() throws IOException {
        String expectedHeader = "Basic " + Base64.getEncoder()
                .encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
        server.createContext("/secure.jar", ex -> {
            String got = ex.getRequestHeaders().getFirst("Authorization");
            if (!expectedHeader.equals(got)) {
                respond(ex, 401, new byte[0]);
            } else {
                respond(ex, 200, "secret".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        Path dest = tempDir.resolve("secure.jar");
        TransportResult result = transport.fetch(
                baseUri().resolve("secure.jar"),
                dest,
                Optional.of(new Credentials("u", "p"))).join();

        assertInstanceOf(TransportResult.Ok.class, result);
    }

    @Test
    void basicAuthUsesUtf8ForNonAsciiCredentials() throws IOException {
        String user = "user";
        String pass = "pässword";
        String expectedHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        server.createContext("/utf.jar", ex -> {
            String got = ex.getRequestHeaders().getFirst("Authorization");
            if (!expectedHeader.equals(got)) {
                respond(ex, 401, new byte[0]);
            } else {
                respond(ex, 200, "ok".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        Path dest = tempDir.resolve("utf.jar");
        TransportResult result = transport.fetch(
                baseUri().resolve("utf.jar"),
                dest,
                Optional.of(new Credentials(user, pass))).join();

        assertInstanceOf(TransportResult.Ok.class, result);
    }

    @Test
    void networkFailureLeavesNoPartialFile() {
        server.start();

        Path dest = tempDir.resolve("never.jar");
        TransportResult result = transport.fetch(
                URI.create("http://127.0.0.1:1/never.jar"), dest, Optional.empty()).join();

        assertInstanceOf(TransportResult.Network.class, result);
        assertFalse(Files.exists(dest));
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }
}
