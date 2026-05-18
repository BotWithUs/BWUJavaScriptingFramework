package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.ResolveOutcome;
import com.botwithus.bot.core.resolver.ResolvedArtifact;
import com.botwithus.bot.core.resolver.driver.ArtifactLocation;
import com.botwithus.bot.core.resolver.driver.ListVersionsResult;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import com.botwithus.bot.core.resolver.driver.RepositoryDriver;
import com.botwithus.bot.core.resolver.driver.VersionListing;
import com.botwithus.bot.core.resolver.metadata.ChecksumDigest;
import com.botwithus.bot.core.resolver.pgp.KeyRing;
import com.botwithus.bot.core.resolver.pgp.PgpSignaturePolicy;
import com.botwithus.bot.core.resolver.pgp.PgpVerifier;
import com.botwithus.bot.core.resolver.pgp.SignatureResult;
import com.botwithus.bot.core.resolver.transport.Transport;
import com.botwithus.bot.core.resolver.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * Repository-layout-agnostic artifact resolver. Owns the repository list,
 * the transport, the PGP verifier, and a map of registered
 * {@link RepositoryDriver drivers} keyed by {@link Repository#driverId()}.
 *
 * <p>{@link #resolve(MavenCoord, Function)} walks the repository list in
 * order, returning the first {@link ResolveOutcome.Resolved}. For each
 * repository the resolver:
 * <ol>
 *   <li>Looks up the driver by {@code repo.driverId()}. Unknown driver →
 *       {@link ResolveOutcome.NotFound} with an actionable message.</li>
 *   <li>Asks the driver to list versions (when the coordinate has no
 *       version). Maps {@link ListVersionsResult} variants onto
 *       {@code ResolveOutcome}.</li>
 *   <li>Asks the driver to locate the jar, checksum, and (when policy
 *       demands) signature; fetches each via the transport.</li>
 *   <li>Verifies checksum (SHA-256 first, SHA-1 fallback for legacy
 *       Maven Central) and PGP signature.</li>
 * </ol>
 *
 * <p>Failures are ranked by severity (ChecksumMismatch ≻ SignatureInvalid
 * ≻ TransportFailure ≻ NotFound) so the user sees the most informative
 * error from the first repository that produced one.</p>
 */
public final class Resolver {

    private static final Logger log = LoggerFactory.getLogger(Resolver.class);
    private static final String STAGE_METADATA = "metadata";
    private static final String STAGE_JAR = "jar";
    private static final String STAGE_SHA256 = "sha256";
    private static final String STAGE_ASC = "asc";
    private static final String FRESH_STAGING_PREFIX = "bwu-resolver-";
    private static final String SHA256_SUFFIX = ".sha256";
    private static final String ASC_SUFFIX = ".asc";

    private final List<Repository> repositories;
    private final Transport transport;
    private final Map<String, RepositoryDriver> driversByTypeId;
    private final PgpVerifier pgpVerifier;
    private final Path stagingRoot;
    private final PgpPolicyLookup pgpPolicyLookup;

    /**
     * Convenience factory that discovers drivers via
     * {@link ServiceLoader}. Production wiring uses this; tests pass a
     * concrete driver map to the explicit constructor.
     */
    public static Resolver withDiscoveredDrivers(List<Repository> repositories, Transport transport,
                                                 PgpVerifier pgpVerifier, Path stagingRoot) {
        Map<String, RepositoryDriver> discovered = new LinkedHashMap<>();
        discovered.put(MavenRepositoryDriver.TYPE_ID, new MavenRepositoryDriver());
        for (RepositoryDriver driver : ServiceLoader.load(RepositoryDriver.class)) {
            discovered.putIfAbsent(driver.typeId(), driver);
        }
        return new Resolver(repositories, transport, discovered, pgpVerifier, stagingRoot);
    }

    public Resolver(List<Repository> repositories, Transport transport,
                    Map<String, RepositoryDriver> drivers,
                    PgpVerifier pgpVerifier, Path stagingRoot) {
        this(repositories, transport, drivers, pgpVerifier, stagingRoot, defaultPolicyLookup());
    }

    public Resolver(List<Repository> repositories,
                    Transport transport,
                    Map<String, RepositoryDriver> drivers,
                    PgpVerifier pgpVerifier,
                    Path stagingRoot,
                    PgpPolicyLookup pgpPolicyLookup) {
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(drivers, "drivers");
        Objects.requireNonNull(pgpVerifier, "pgpVerifier");
        Objects.requireNonNull(stagingRoot, "stagingRoot");
        Objects.requireNonNull(pgpPolicyLookup, "pgpPolicyLookup");
        this.repositories = List.copyOf(repositories);
        this.transport = transport;
        this.driversByTypeId = Map.copyOf(drivers);
        this.pgpVerifier = pgpVerifier;
        this.stagingRoot = stagingRoot;
        this.pgpPolicyLookup = pgpPolicyLookup;
    }

    public List<Repository> repositories() {
        return repositories;
    }

    public Map<String, RepositoryDriver> drivers() {
        return driversByTypeId;
    }

    public ResolveOutcome resolve(MavenCoord coord) {
        return resolve(coord, ignored -> Optional.empty());
    }

    public ResolveOutcome resolve(MavenCoord coord, Function<Repository, Optional<Credentials>> credentialsLookup) {
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(credentialsLookup, "credentialsLookup");
        if (repositories.isEmpty()) {
            return new ResolveOutcome.NotFound(coord, "no repositories configured");
        }

        ResolveOutcome best = null;
        for (Repository repo : repositories) {
            Optional<Credentials> creds = credentialsLookup.apply(repo);
            ResolveOutcome outcome = tryRepository(coord, repo, creds);
            if (isResolved(outcome)) {
                return outcome;
            }
            best = preferBetter(best, outcome);
        }
        return best != null ? best : new ResolveOutcome.NotFound(coord, "exhausted repository list");
    }

    private static boolean isResolved(ResolveOutcome outcome) {
        return switch (outcome) {
            case ResolveOutcome.Resolved r -> true;
            case ResolveOutcome.NotFound nf -> false;
            case ResolveOutcome.ChecksumMismatch cm -> false;
            case ResolveOutcome.SignatureInvalid si -> false;
            case ResolveOutcome.TransportFailure tf -> false;
        };
    }

    private ResolveOutcome tryRepository(MavenCoord coord, Repository repository, Optional<Credentials> credentials) {
        RepositoryDriver driver = driversByTypeId.get(repository.driverId());
        if (driver == null) {
            return new ResolveOutcome.NotFound(coord,
                    "repository " + repository.id() + " uses unknown driver '" + repository.driverId() + "'");
        }

        Path stagingDir;
        try {
            Files.createDirectories(stagingRoot);
            stagingDir = Files.createTempDirectory(stagingRoot, FRESH_STAGING_PREFIX);
        } catch (IOException e) {
            log.warn("failed to create staging dir under {}", stagingRoot, e);
            return new ResolveOutcome.TransportFailure(coord, repository, STAGE_METADATA,
                    new TransportResult.Network(stagingRoot.toString(), e));
        }

        Optional<String> resolvedVersion = resolveVersion(driver, coord, repository, credentials);
        ResolveOutcome failure = listingOutcomeIfMissing(resolvedVersion, driver, coord, repository, credentials);
        if (failure != null) {
            return failure;
        }
        String version = resolvedVersion.orElseThrow();
        return downloadAndVerify(driver, MavenCoord.of(coord.groupId(), coord.artifactId(), version),
                version, repository, credentials, stagingDir);
    }

    private Optional<String> resolveVersion(RepositoryDriver driver, MavenCoord coord, Repository repository,
                                            Optional<Credentials> credentials) {
        if (coord.version().isPresent()) {
            return coord.version();
        }
        ListVersionsResult listing = driver.listVersions(repository, coord, transport, credentials).join();
        return switch (listing) {
            case ListVersionsResult.Ok ok -> ok.listing().bestRelease();
            case ListVersionsResult.NotIndexed nf -> Optional.empty();
            case ListVersionsResult.Malformed bad -> Optional.empty();
            case ListVersionsResult.TransportFailed tf -> Optional.empty();
        };
    }

    /**
     * Returns the {@link ResolveOutcome} to surface when the version
     * could not be resolved, or {@code null} when {@code resolvedVersion}
     * is present. Re-runs the listing only when needed to capture the
     * specific failure reason — the happy path takes no extra round-trip.
     */
    private ResolveOutcome listingOutcomeIfMissing(Optional<String> resolvedVersion, RepositoryDriver driver,
                                                   MavenCoord coord, Repository repository,
                                                   Optional<Credentials> credentials) {
        if (resolvedVersion.isPresent()) {
            return null;
        }
        if (coord.version().isPresent()) {
            return null; // unreachable given resolveVersion, but defensive
        }
        ListVersionsResult listing = driver.listVersions(repository, coord, transport, credentials).join();
        return switch (listing) {
            case ListVersionsResult.Ok ok ->
                    new ResolveOutcome.NotFound(coord, "no versions listed for " + coord);
            case ListVersionsResult.NotIndexed nf -> new ResolveOutcome.NotFound(coord, nf.reason());
            case ListVersionsResult.Malformed bad -> new ResolveOutcome.NotFound(coord, bad.reason());
            case ListVersionsResult.TransportFailed tf ->
                    new ResolveOutcome.TransportFailure(coord, repository, STAGE_METADATA, tf.cause());
        };
    }

    private ResolveOutcome downloadAndVerify(RepositoryDriver driver, MavenCoord coord, String version,
                                             Repository repository, Optional<Credentials> credentials,
                                             Path stagingDir) {
        ArtifactLocation jarLoc = driver.locateJar(repository, coord, version);
        URI jarUri = urlOrNull(jarLoc);
        if (jarUri == null) {
            return new ResolveOutcome.NotFound(coord, missingReason(jarLoc));
        }
        Path jarStaging = stagingDir.resolve(coord.jarFileName(version));
        TransportResult jarFetch = transport.fetch(jarUri, jarStaging, credentials).join();
        Path jarPath = okPath(jarFetch);
        if (jarPath == null) {
            return jarTransportToOutcome(coord, repository, STAGE_JAR, jarFetch);
        }

        ResolveOutcome checksumOutcome = verifyChecksum(driver, coord, version, repository, credentials,
                stagingDir, jarPath);
        if (checksumOutcome != null) {
            return checksumOutcome;
        }

        return switch (pgpPolicyLookup.policyFor(repository)) {
            case PgpSignaturePolicy.Required req ->
                    resolveWithSignature(driver, coord, version, repository, credentials, stagingDir, jarPath,
                            req.keyRing());
            case PgpSignaturePolicy.NotRequired nr ->
                    new ResolveOutcome.Resolved(new ResolvedArtifact(
                            coord, repository, jarPath, Optional.empty(), readDigestUnchecked(jarPath)));
        };
    }

    private ResolveOutcome resolveWithSignature(RepositoryDriver driver, MavenCoord coord, String version,
                                                Repository repository, Optional<Credentials> credentials,
                                                Path stagingDir, Path jarPath, KeyRing keyRing) {
        ResolveOutcome sigOutcome = verifySignature(driver, coord, version, repository, credentials, stagingDir,
                jarPath, keyRing);
        if (sigOutcome != null) {
            return sigOutcome;
        }
        return new ResolveOutcome.Resolved(new ResolvedArtifact(
                coord, repository, jarPath,
                Optional.of(stagingDir.resolve(coord.jarFileName(version) + ASC_SUFFIX)),
                readDigestUnchecked(jarPath)));
    }

    private ResolveOutcome verifyChecksum(RepositoryDriver driver, MavenCoord coord, String version,
                                          Repository repository, Optional<Credentials> credentials,
                                          Path stagingDir, Path jarFile) {
        ArtifactLocation shaLoc = driver.locateChecksum(repository, coord, version);
        URI shaUri = urlOrNull(shaLoc);
        if (shaUri == null) {
            return verifyChecksumLegacyFallback(driver, coord, version, repository, credentials, stagingDir, jarFile);
        }
        Path shaStaging = stagingDir.resolve(coord.jarFileName(version) + SHA256_SUFFIX);
        TransportResult shaFetch = transport.fetch(shaUri, shaStaging, credentials).join();
        return switch (shaFetch) {
            case TransportResult.NotFound nf ->
                    verifyChecksumLegacyFallback(driver, coord, version, repository, credentials, stagingDir, jarFile);
            case TransportResult.HttpError he -> jarTransportToOutcome(coord, repository, STAGE_SHA256, shaFetch);
            case TransportResult.Network n -> jarTransportToOutcome(coord, repository, STAGE_SHA256, shaFetch);
            case TransportResult.Ok ok -> verifySha256Against(coord, repository, jarFile, ok.localPath());
        };
    }

    private ResolveOutcome verifySha256Against(MavenCoord coord, Repository repository, Path jarFile, Path shaPath) {
        try {
            String hex = Files.readString(shaPath).trim();
            Optional<ChecksumDigest> expected = ChecksumDigest.parseHex(hex);
            if (expected.isEmpty()) {
                return new ResolveOutcome.TransportFailure(coord, repository, STAGE_SHA256,
                        new TransportResult.Network(shaPath.toString(),
                                new IOException("unparseable SHA-256 file: " + hex)));
            }
            ChecksumDigest actual = ChecksumDigest.of(jarFile);
            if (!expected.get().matches(actual)) {
                return new ResolveOutcome.ChecksumMismatch(coord, repository,
                        expected.get().sha256(), actual.sha256());
            }
            return null;
        } catch (IOException e) {
            return new ResolveOutcome.TransportFailure(coord, repository, STAGE_SHA256,
                    new TransportResult.Network(shaPath.toString(), e));
        }
    }

    /**
     * Legacy-checksum fallback hook. The driver can return a non-Maven
     * checksum (SHA-1 for older Maven Central artifacts); 12.1 ships the
     * Maven driver with SHA-256-only verification, and 12.2 reintroduces
     * the {@code Sha1Digest} parser. Until then, an absent SHA-256
     * surfaces as {@link ResolveOutcome.NotFound} via
     * {@link #jarTransportToOutcome}.
     */
    private ResolveOutcome verifyChecksumLegacyFallback(RepositoryDriver driver, MavenCoord coord, String version,
                                                        Repository repository, Optional<Credentials> credentials,
                                                        Path stagingDir, Path jarFile) {
        return new ResolveOutcome.NotFound(coord,
                repository.id() + " has no SHA-256 checksum for " + coord + ":" + version);
    }

    private ResolveOutcome verifySignature(RepositoryDriver driver, MavenCoord coord, String version,
                                           Repository repository, Optional<Credentials> credentials,
                                           Path stagingDir, Path jarFile, KeyRing keyRing) {
        ArtifactLocation ascLoc = driver.locateSignature(repository, coord, version);
        URI ascUri = urlOrNull(ascLoc);
        if (ascUri == null) {
            return new ResolveOutcome.SignatureInvalid(coord, repository, SignatureResult.MissingSignatureFile.INSTANCE);
        }
        Path ascStaging = stagingDir.resolve(coord.jarFileName(version) + ASC_SUFFIX);
        TransportResult ascFetch = transport.fetch(ascUri, ascStaging, credentials).join();
        return switch (ascFetch) {
            case TransportResult.NotFound nf ->
                    new ResolveOutcome.SignatureInvalid(coord, repository, SignatureResult.MissingSignatureFile.INSTANCE);
            case TransportResult.HttpError he -> jarTransportToOutcome(coord, repository, STAGE_ASC, ascFetch);
            case TransportResult.Network n -> jarTransportToOutcome(coord, repository, STAGE_ASC, ascFetch);
            case TransportResult.Ok ok -> checkSignature(jarFile, ok.localPath(), keyRing, coord, repository);
        };
    }

    private ResolveOutcome checkSignature(Path jarFile, Path ascFile, KeyRing keyRing,
                                          MavenCoord coord, Repository repository) {
        SignatureResult result = pgpVerifier.verify(jarFile, ascFile, keyRing);
        return switch (result) {
            case SignatureResult.Verified v -> null;
            case SignatureResult.InvalidSignature inv -> new ResolveOutcome.SignatureInvalid(coord, repository, result);
            case SignatureResult.UnknownKey uk -> new ResolveOutcome.SignatureInvalid(coord, repository, result);
            case SignatureResult.MissingSignatureFile m -> new ResolveOutcome.SignatureInvalid(coord, repository, result);
        };
    }

    private ResolveOutcome jarTransportToOutcome(MavenCoord coord, Repository repository, String stage,
                                                 TransportResult cause) {
        return switch (cause) {
            case TransportResult.NotFound nf ->
                    new ResolveOutcome.NotFound(coord, repository.id() + " missing " + stage);
            case TransportResult.Ok ok ->
                    new ResolveOutcome.TransportFailure(coord, repository, stage, cause);
            case TransportResult.HttpError he ->
                    new ResolveOutcome.TransportFailure(coord, repository, stage, cause);
            case TransportResult.Network n ->
                    new ResolveOutcome.TransportFailure(coord, repository, stage, cause);
        };
    }

    /**
     * Extracts the URI from a successful {@link ArtifactLocation.Url}, or
     * returns {@code null} for {@link ArtifactLocation.Missing}. Sealed
     * switch — the compiler enforces that new {@code ArtifactLocation}
     * variants are handled here.
     */
    private static URI urlOrNull(ArtifactLocation loc) {
        return switch (loc) {
            case ArtifactLocation.Url u -> u.uri();
            case ArtifactLocation.Missing m -> null;
        };
    }

    private static String missingReason(ArtifactLocation loc) {
        return switch (loc) {
            case ArtifactLocation.Url u -> "(unreachable: Url has no reason)";
            case ArtifactLocation.Missing m -> m.reason();
        };
    }

    /**
     * Extracts the local path from a successful {@link TransportResult.Ok},
     * or returns {@code null} for any failure variant.
     */
    private static Path okPath(TransportResult result) {
        return switch (result) {
            case TransportResult.Ok ok -> ok.localPath();
            case TransportResult.NotFound nf -> null;
            case TransportResult.HttpError he -> null;
            case TransportResult.Network n -> null;
        };
    }

    private static byte[] readDigestUnchecked(Path jar) {
        try {
            return ChecksumDigest.of(jar).sha256();
        } catch (IOException e) {
            throw new IllegalStateException("digest re-read failed for " + jar, e);
        }
    }

    /**
     * Prefers a more-informative outcome over a less-informative one when
     * walking the repository list. Order: ChecksumMismatch > SignatureInvalid
     * > TransportFailure > NotFound.
     */
    private static ResolveOutcome preferBetter(ResolveOutcome accumulated, ResolveOutcome candidate) {
        if (accumulated == null) {
            return candidate;
        }
        return severity(candidate) > severity(accumulated) ? candidate : accumulated;
    }

    private static int severity(ResolveOutcome outcome) {
        return switch (outcome) {
            case ResolveOutcome.Resolved ignored -> 4;
            case ResolveOutcome.ChecksumMismatch ignored -> 3;
            case ResolveOutcome.SignatureInvalid ignored -> 2;
            case ResolveOutcome.TransportFailure ignored -> 1;
            case ResolveOutcome.NotFound ignored -> 0;
        };
    }

    /** Per-repository PGP policy lookup. Defaults all repos to NotRequired. */
    @FunctionalInterface
    public interface PgpPolicyLookup {
        PgpSignaturePolicy policyFor(Repository repository);
    }

    private static PgpPolicyLookup defaultPolicyLookup() {
        return repo -> PgpSignaturePolicy.NotRequired.INSTANCE;
    }

    /**
     * Garbage-collects staging directories older than {@code ageThreshold}.
     */
    public void cleanupOldStaging(Duration ageThreshold) {
        Objects.requireNonNull(ageThreshold, "ageThreshold");
        if (!Files.isDirectory(stagingRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(ageThreshold);
        try {
            try (var paths = Files.list(stagingRoot)) {
                paths.filter(Files::isDirectory)
                        .filter(p -> isOlderThan(p, cutoff))
                        .forEach(Resolver::deleteRecursively);
            }
        } catch (IOException e) {
            log.debug("staging cleanup pass failed under {}", stagingRoot, e);
        }
    }

    private static boolean isOlderThan(Path p, Instant cutoff) {
        try {
            FileTime mt = Files.getLastModifiedTime(p);
            return mt.toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
        try {
            try (var paths = Files.walk(root)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.debug("failed to delete {} during staging cleanup", p, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.debug("recursive delete failed for {}", root, e);
        }
    }
}
