package com.botwithus.bot.core.resolver.search;

import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.SearchOutcome;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import com.botwithus.bot.core.resolver.driver.RepositoryDriver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    private HttpServer server;
    private SearchService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, RepositoryDriver> drivers = Map.of(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        service = new SearchService(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                drivers,
                repo -> Optional.empty());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private Repository nexusRepoForSearch(String path) {
        return new Repository(
                "internal",
                URI.create("https://nexus.example/"),
                MavenRepositoryDriver.TYPE_ID,
                false, false,
                Optional.empty(),
                Optional.of(endpoint(path)));
    }

    @Test
    void parsesNexusRestResponse() throws IOException {
        String body = """
                {"items":[
                  {"group":"com.example","name":"art","version":"1.0.0"},
                  {"group":"com.example","name":"other","version":"2.0.0"}
                ]}
                """;
        server.createContext("/service/rest/v1/search",
                ex -> respond(ex, 200, body.getBytes(StandardCharsets.UTF_8)));
        server.start();

        SearchOutcome outcome = service.search(nexusRepoForSearch("/service/rest/v1/search"), "art", 50);
        SearchOutcome.Hits hits = assertInstanceOf(SearchOutcome.Hits.class, outcome);
        assertEquals(2, hits.hits().size());
        assertEquals("com.example", hits.hits().get(0).coord().groupId());
        assertEquals("art", hits.hits().get(0).coord().artifactId());
        assertEquals("1.0.0", hits.hits().get(0).latestVersion());
    }

    @Test
    void unsupportedWhenNoSearchEndpoint() {
        Repository repo = Repository.mavenRelease(
                "nope", URI.create("https://example.invalid/"), false);
        SearchOutcome outcome = service.search(repo, "anything", 10);
        assertInstanceOf(SearchOutcome.NotSupported.class, outcome);
    }

    @Test
    void unknownDriverYieldsNotSupported() {
        Repository repo = new Repository(
                "weird", URI.create("https://example.invalid/"), "no-such-driver",
                false, false, Optional.empty(), Optional.of(endpoint("/")));
        SearchOutcome outcome = service.search(repo, "anything", 10);
        assertInstanceOf(SearchOutcome.NotSupported.class, outcome);
    }

    @Test
    void notFoundResponseYieldsNotSupported() throws IOException {
        server.createContext("/", ex -> respond(ex, 404, new byte[0]));
        server.start();
        SearchOutcome outcome = service.search(nexusRepoForSearch("/search"), "x", 10);
        assertInstanceOf(SearchOutcome.NotSupported.class, outcome);
    }

    @Test
    void serverErrorYieldsTransportFailure() throws IOException {
        server.createContext("/", ex -> respond(ex, 500, "boom".getBytes(StandardCharsets.UTF_8)));
        server.start();
        SearchOutcome outcome = service.search(nexusRepoForSearch("/search"), "x", 10);
        assertInstanceOf(SearchOutcome.TransportFailure.class, outcome);
    }

    @Test
    void emptyResponseYieldsEmptyHits() throws IOException {
        server.createContext("/", ex -> respond(ex, 200, "{\"items\":[]}".getBytes(StandardCharsets.UTF_8)));
        server.start();
        SearchOutcome outcome = service.search(nexusRepoForSearch("/search"), "x", 10);
        SearchOutcome.Hits hits = assertInstanceOf(SearchOutcome.Hits.class, outcome);
        assertTrue(hits.hits().isEmpty());
    }

    @Test
    void malformedJsonYieldsEmptyHits() throws IOException {
        // The driver's response parser is exception-safe: malformed JSON yields
        // an empty list of hits, not a thrown exception. SearchService maps
        // that to Hits(empty) rather than TransportFailure (the HTTP call did
        // succeed; only the response parsing failed).
        server.createContext("/", ex -> respond(ex, 200, "{not json".getBytes(StandardCharsets.UTF_8)));
        server.start();
        SearchOutcome outcome = service.search(nexusRepoForSearch("/search"), "x", 10);
        SearchOutcome.Hits hits = assertInstanceOf(SearchOutcome.Hits.class, outcome);
        assertTrue(hits.hits().isEmpty());
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
