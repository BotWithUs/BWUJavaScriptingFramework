package com.botwithus.bot.core.alerts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The real JDK transport against an in-process HTTP server on the loopback
 * interface. Nothing leaves the machine.
 */
class JdkHttpTransportTest {

    private static final int BACKLOG = 0;
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);

    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedAuth = new AtomicReference<>();
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private JdkHttpTransport transport;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), BACKLOG);
        server.createContext("/ok", exchange -> {
            record(exchange);
            respond(exchange, 200, "{\"id\":\"abc\"}", Map.of("X-Test", "yes"));
        });
        server.createContext("/denied", exchange -> respond(exchange, 401, "unauthorized", Map.of()));
        server.createContext("/moved", exchange ->
                respond(exchange, 301, "", Map.of("Location", uri("/ok").toString())));
        server.createContext("/slow", exchange -> {
            awaitRelease();
            respond(exchange, 200, "late", Map.of());
        });
        server.start();
        transport = new JdkHttpTransport(SHORT_TIMEOUT);
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        transport.close();
        server.stop(0);
    }

    @Test
    void post_sendsBodyAndHeaders_andReadsTheReply() throws Exception {
        HttpReply reply = transport.post(new HttpPost(uri("/ok"),
                Map.of("Authorization", "Bearer tk", "Content-Type", "text/plain; charset=utf-8"), "héllo"));

        assertEquals(200, reply.status());
        assertEquals("{\"id\":\"abc\"}", reply.body());
        assertEquals("yes", reply.header("x-test").orElseThrow());
        assertEquals("POST", receivedMethod.get());
        assertEquals("héllo", receivedBody.get());
        assertEquals("Bearer tk", receivedAuth.get());
    }

    @Test
    void post_errorStatus_isAReplyNotAnException() throws Exception {
        HttpReply reply = transport.post(new HttpPost(uri("/denied"), Map.of(), ""));

        assertEquals(401, reply.status());
        assertEquals("unauthorized", reply.body());
    }

    @Test
    void post_redirect_isNotFollowed() throws Exception {
        assertEquals(301, transport.post(new HttpPost(uri("/moved"), Map.of(), "")).status());
        assertNull(receivedMethod.get(), "the redirect target must not be posted to");
    }

    @Test
    void post_slowServer_timesOut() {
        assertThrows(HttpTimeoutException.class,
                () -> transport.post(new HttpPost(uri("/slow"), Map.of(), "")));
    }

    private URI uri(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + path);
    }

    private void record(HttpExchange exchange) throws IOException {
        receivedMethod.set(exchange.getRequestMethod());
        receivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void awaitRelease() {
        try {
            release.await(SHORT_TIMEOUT.toMillis() * 10, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, Map<String, String> headers)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
