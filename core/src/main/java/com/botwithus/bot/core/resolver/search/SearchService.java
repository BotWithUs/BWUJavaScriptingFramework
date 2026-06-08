package com.botwithus.bot.core.resolver.search;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.SearchOutcome;
import com.botwithus.bot.core.resolver.driver.RepositoryDriver;
import com.botwithus.bot.core.resolver.driver.SearchProtocol;
import com.botwithus.bot.core.resolver.transport.HttpStatus;
import com.botwithus.bot.core.resolver.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Search orchestrator. Delegates URL construction and response parsing
 * to the driver's {@link SearchProtocol} — knows nothing about Solr,
 * Nexus JSON, or any other dialect. The driver describes its native
 * search; this class issues the HTTP request and dispatches the body to
 * the driver's parser.
 *
 * <p>Repositories whose driver returns {@link Optional#empty()} from
 * {@link RepositoryDriver#search} yield {@link SearchOutcome.NotSupported}.
 * 404 responses are also mapped to {@code NotSupported} (the endpoint
 * doesn't exist); other non-200 responses map to
 * {@link SearchOutcome.TransportFailure}.</p>
 */
public final class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String USER_AGENT_VALUE = "botwithus-resolver/1";
    private static final String AUTH_HEADER = "Authorization";
    private static final String AUTH_SCHEME_BASIC = "Basic ";

    private final HttpClient client;
    private final Function<Repository, Optional<Credentials>> credentialsLookup;
    private final Map<String, RepositoryDriver> driversByTypeId;

    public SearchService(HttpClient client,
                         Map<String, RepositoryDriver> drivers,
                         Function<Repository, Optional<Credentials>> credentialsLookup) {
        this.client = Objects.requireNonNull(client, "client");
        this.driversByTypeId = Map.copyOf(Objects.requireNonNull(drivers, "drivers"));
        this.credentialsLookup = Objects.requireNonNull(credentialsLookup, "credentialsLookup");
    }

    /** Searches every repository in order; aggregates the outcomes. */
    public List<SearchOutcome> searchAll(List<Repository> repositories, String query, int limit) {
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(query, "query");
        List<SearchOutcome> outcomes = new ArrayList<>();
        for (Repository repo : repositories) {
            outcomes.add(search(repo, query, limit));
        }
        return List.copyOf(outcomes);
    }

    public SearchOutcome search(Repository repo, String query, int limit) {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(query, "query");

        RepositoryDriver driver = driversByTypeId.get(repo.driverId());
        if (driver == null) {
            return new SearchOutcome.NotSupported(repo, "unknown driver '" + repo.driverId() + "'");
        }
        Optional<SearchProtocol> protocolOpt = driver.search(repo);
        if (protocolOpt.isEmpty()) {
            return new SearchOutcome.NotSupported(repo, "driver " + driver.typeId() + " has no search protocol configured");
        }
        SearchProtocol protocol = protocolOpt.get();
        URI request = protocol.requestBuilder().build(protocol.endpoint(), query, limit);

        HttpRequest.Builder builder = HttpRequest.newBuilder(request)
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .header(USER_AGENT_HEADER, USER_AGENT_VALUE)
                .GET();
        credentialsLookup.apply(repo).ifPresent(c -> builder.header(AUTH_HEADER, basicAuth(c)));

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new SearchOutcome.TransportFailure(repo, new TransportResult.Network(request.toString(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SearchOutcome.TransportFailure(repo, new TransportResult.Network(request.toString(), new IOException(e)));
        }

        int status = response.statusCode();
        if (status == HttpStatus.NOT_FOUND) {
            return new SearchOutcome.NotSupported(repo, "endpoint returned 404");
        }
        if (status != HttpStatus.OK) {
            return new SearchOutcome.TransportFailure(repo,
                    new TransportResult.HttpError(request.toString(), status, "HTTP " + status));
        }

        List<SearchOutcome.Hit> hits = protocol.responseParser().apply(response.body());
        log.debug("search {} on {} returned {} hit(s)", query, repo.id(), hits.size());
        return new SearchOutcome.Hits(hits);
    }

    private static String basicAuth(Credentials c) {
        String pair = c.username() + ":" + c.password();
        return AUTH_SCHEME_BASIC + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
