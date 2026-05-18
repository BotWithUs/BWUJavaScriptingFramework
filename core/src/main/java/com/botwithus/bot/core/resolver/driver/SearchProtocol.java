package com.botwithus.bot.core.resolver.driver;

import com.botwithus.bot.core.resolver.SearchOutcome;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Repository-native search shape. Drivers that support search return a
 * {@code SearchProtocol} from {@link RepositoryDriver#search}; drivers
 * that don't return {@link java.util.Optional#empty()} and the CLI
 * renders {@code SearchOutcome.NotSupported}.
 *
 * <p>Function-typed parameters per java-patterns — a search protocol is
 * (effectively) two pure functions: how to build a request URI, how to
 * parse a response body into hits. The HTTP client and credential
 * lookup are orchestrated by {@code SearchService}, which owns the IO;
 * the protocol is pure CPU.</p>
 */
public record SearchProtocol(
        URI endpoint,
        RequestBuilder requestBuilder,
        Function<String, List<SearchOutcome.Hit>> responseParser) {

    public SearchProtocol {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(requestBuilder, "requestBuilder");
        Objects.requireNonNull(responseParser, "responseParser");
    }

    /**
     * Builds the search URI for one (query, limit) pair. Implementations
     * are pure — they read the {@link SearchProtocol#endpoint} and append
     * query parameters specific to the repository's search dialect (Solr
     * for Maven Central, REST query params for Nexus).
     */
    @FunctionalInterface
    public interface RequestBuilder {
        URI build(URI endpoint, String query, int limit);
    }
}
