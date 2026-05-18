package com.botwithus.bot.core.resolver;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Maven repository configuration entry. Persisted to
 * {@code ~/.botwithus/repositories.json} via {@code RepositoryConfigStore}
 * (introduced in 12.2).
 *
 * <p>{@code credentialsRef} is the key into the credentials store; usually
 * the same as {@link #id()}, but kept distinct so a token can be shared
 * across multiple repository entries that point at the same Nexus host.</p>
 *
 * <p>{@code searchEndpoint} is the repository-native search URL: Maven
 * Central's {@code search.maven.org}, Nexus's {@code service/rest/v1/search},
 * etc. Absent when the repository does not support search.</p>
 */
public record Repository(
        String id,
        URI url,
        RepoType type,
        boolean requireSignature,
        Optional<String> credentialsRef,
        Optional<URI> searchEndpoint) {

    public Repository {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(credentialsRef, "credentialsRef");
        Objects.requireNonNull(searchEndpoint, "searchEndpoint");
        if (id.isBlank()) {
            throw new IllegalArgumentException("repository id must not be blank");
        }
        url = ensureTrailingSlash(url);
    }

    /**
     * Convenience constructor for repositories with no credentials and no
     * dedicated search endpoint.
     */
    public static Repository unauthenticated(String id, URI url, RepoType type, boolean requireSignature) {
        return new Repository(id, url, type, requireSignature, Optional.empty(), Optional.empty());
    }

    /**
     * Maven base URLs must end with {@code /} for {@link URI#resolve(String)}
     * to append rather than replace the last path segment. Repository
     * configs from disk or CLI input are normalized here so callers don't
     * have to remember to append the slash themselves.
     */
    private static URI ensureTrailingSlash(URI url) {
        String s = url.toString();
        return s.endsWith("/") ? url : URI.create(s + "/");
    }
}
