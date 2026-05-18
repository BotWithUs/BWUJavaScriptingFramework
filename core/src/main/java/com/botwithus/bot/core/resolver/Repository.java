package com.botwithus.bot.core.resolver;

import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository configuration entry. Persisted to
 * {@code ~/.botwithus/repositories.json} via {@code RepositoryConfigStore}
 * (introduced in 12.2).
 *
 * <p>{@link #driverId} names the {@code RepositoryDriver} responsible for
 * this repository's layout (e.g., {@code "maven"} for the standard
 * {@code groupId/artifactId/version/...} shape, {@code "github-releases"}
 * for the GitHub Releases API). Drivers are discovered via
 * {@link java.util.ServiceLoader}; an unrecognised {@code driverId} causes
 * the resolver to skip the repository with an actionable error.</p>
 *
 * <p>{@link #snapshots} is a Maven-specific hint — release-only Maven
 * repositories refuse {@code -SNAPSHOT} versions. Non-Maven drivers
 * ignore this field.</p>
 *
 * <p>{@link #credentialsRef} is the key into the credentials store;
 * usually the same as {@link #id()}, but kept distinct so a token can be
 * shared across multiple repository entries that point at the same
 * host.</p>
 *
 * <p>{@link #searchEndpoint} is the repository-native search URL (Maven
 * Central's {@code search.maven.org}, Nexus's
 * {@code service/rest/v1/search}, etc.). Absent when the repository does
 * not support search.</p>
 */
public record Repository(
        String id,
        URI url,
        String driverId,
        boolean snapshots,
        boolean requireSignature,
        Optional<String> credentialsRef,
        Optional<URI> searchEndpoint) {

    public Repository {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(credentialsRef, "credentialsRef");
        Objects.requireNonNull(searchEndpoint, "searchEndpoint");
        if (id.isBlank()) {
            throw new IllegalArgumentException("repository id must not be blank");
        }
        if (driverId.isBlank()) {
            throw new IllegalArgumentException("driverId must not be blank");
        }
        url = ensureTrailingSlash(url);
    }

    /**
     * Convenience constructor for the standard Maven layout with no
     * credentials and no dedicated search endpoint.
     */
    public static Repository maven(String id, URI url, boolean snapshots, boolean requireSignature) {
        return new Repository(id, url, MavenRepositoryDriver.TYPE_ID, snapshots, requireSignature,
                Optional.empty(), Optional.empty());
    }

    /**
     * Convenience constructor for a Maven release-only repository — the
     * common case for test fixtures.
     */
    public static Repository mavenRelease(String id, URI url, boolean requireSignature) {
        return maven(id, url, /* snapshots */ false, requireSignature);
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
