package com.botwithus.bot.core.resolver.driver;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.transport.Transport;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * SPI for repository-layout-specific behavior. The resolver knows how to
 * fetch artifacts, verify checksums, and verify signatures; it does NOT
 * know how to find them — that's the driver's job.
 *
 * <p>Intentionally <strong>not sealed</strong>. Users plug in third-party
 * drivers via {@link java.util.ServiceLoader} (GitHub releases, custom
 * JSON-index endpoints, flat HTTP directory listings, etc.) by adding a
 * service registration under
 * {@code META-INF/services/com.botwithus.bot.core.resolver.driver.RepositoryDriver}.</p>
 *
 * <p>Implementations must be stateless and thread-safe — one driver
 * instance is shared across the whole resolver lifetime. Per-request
 * state (the transport, credentials, the coordinate) is passed by
 * argument.</p>
 *
 * <p>{@link MavenCoord} is the canonical coordinate type for all
 * drivers; non-Maven drivers reinterpret its fields. For a GitHub-
 * releases driver, {@code groupId} is the owner ({@code anthropics})
 * and {@code artifactId} is the repository name ({@code claude-code}).
 * Drivers that need additional addressing carry it in {@link Repository}
 * fields, not in the coordinate.</p>
 */
public interface RepositoryDriver {

    /**
     * Unique identifier matched against {@link Repository#driverId()}.
     * Convention: lowercase, hyphenated, no whitespace. Examples:
     * {@code "maven"}, {@code "github-releases"}, {@code "json-index"}.
     */
    String typeId();

    /**
     * Lists every version this repository knows about for {@code coord}.
     * Async because the discovery may require an HTTP round-trip; the
     * resolver joins via {@code .join()}.
     *
     * <p>The driver chooses its own metadata format (Maven's
     * {@code maven-metadata.xml}, GitHub's
     * {@code /repos/{o}/{r}/releases} JSON, etc.). The {@link Transport}
     * is supplied so the driver can issue authenticated fetches without
     * holding a reference to a shared HTTP client.</p>
     */
    CompletableFuture<ListVersionsResult> listVersions(
            Repository repository,
            MavenCoord coord,
            Transport transport,
            Optional<Credentials> credentials);

    /**
     * Returns the URL of the JAR for {@code coord} at {@code version} in
     * {@code repository}, or {@link ArtifactLocation.Missing} if the
     * driver cannot construct one (e.g., the coordinate format isn't
     * compatible with the layout).
     *
     * <p>Pure URL construction — no I/O. The resolver fetches via the
     * transport and surfaces 404s as {@code NotFound}.</p>
     */
    ArtifactLocation locateJar(Repository repository, MavenCoord coord, String version);

    /**
     * Returns the URL of the SHA-256 checksum sidecar, or
     * {@link ArtifactLocation.Missing} if the repository layout does not
     * publish checksums. Flat HTTP directory listings often don't.
     */
    ArtifactLocation locateChecksum(Repository repository, MavenCoord coord, String version);

    /**
     * Returns the URL of the legacy SHA-1 checksum sidecar (used by
     * older Maven Central artifacts). Drivers that publish only SHA-256
     * — or no checksum at all — return {@link ArtifactLocation.Missing}.
     *
     * <p>The default implementation returns {@code Missing} so non-Maven
     * drivers don't have to think about SHA-1.</p>
     */
    default ArtifactLocation locateLegacyChecksum(Repository repository, MavenCoord coord, String version) {
        return new ArtifactLocation.Missing("driver does not publish SHA-1 sidecars");
    }

    /**
     * Returns the URL of the detached PGP signature, or
     * {@link ArtifactLocation.Missing} if the repository layout does not
     * publish signatures.
     */
    ArtifactLocation locateSignature(Repository repository, MavenCoord coord, String version);

    /**
     * Optionally returns the repository's native search protocol.
     * Drivers without search return {@link Optional#empty()} and the
     * CLI renders {@code SearchOutcome.NotSupported}.
     */
    default Optional<SearchProtocol> search(Repository repository) {
        return Optional.empty();
    }
}
