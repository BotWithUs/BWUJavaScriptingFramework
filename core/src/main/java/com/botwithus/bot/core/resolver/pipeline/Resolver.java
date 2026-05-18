package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.ResolveOutcome;
import com.botwithus.bot.core.resolver.ResolvedArtifact;
import com.botwithus.bot.core.resolver.metadata.ChecksumDigest;
import com.botwithus.bot.core.resolver.metadata.MavenMetadataParser;
import com.botwithus.bot.core.resolver.pgp.KeyRing;
import com.botwithus.bot.core.resolver.pgp.PgpSignaturePolicy;
import com.botwithus.bot.core.resolver.pgp.PgpVerifier;
import com.botwithus.bot.core.resolver.pgp.SignatureResult;
import com.botwithus.bot.core.resolver.transport.MavenTransport;
import com.botwithus.bot.core.resolver.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Maven artifact resolver — the orchestrator. Owns the repository list,
 * the transport, and the PGP verifier; constructor-injected, no statics.
 *
 * <p>{@link #resolve(MavenCoord, Function)} walks the repository list in
 * order, returning the first {@link ResolveOutcome.Resolved}. Repository
 * failures are surfaced in order of severity: a checksum mismatch from
 * repo A is preferred over a 404 from repo B (the user wants to know
 * about corrupted artifacts even if a fallback exists).</p>
 *
 * <p>The {@code credentialsLookup} argument is a function from repository
 * id to {@link Credentials}; in 12.1 the only caller passes a no-op
 * (returns {@link Optional#empty()}), in 12.2 it is wired to
 * {@code CredentialsStore::lookup}.</p>
 */
public final class Resolver {

    private static final Logger log = LoggerFactory.getLogger(Resolver.class);
    private static final String STAGE_METADATA = "metadata";
    private static final String STAGE_JAR = "jar";
    private static final String STAGE_SHA256 = "sha256";
    private static final String STAGE_ASC = "asc";
    private static final String FRESH_STAGING_PREFIX = "bwu-resolver-";

    private final List<Repository> repositories;
    private final VersionResolver versionResolver;
    private final ArtifactDownloader downloader;
    private final PgpVerifier pgpVerifier;
    private final Path stagingRoot;
    private final PgpPolicyLookup pgpPolicyLookup;

    public Resolver(List<Repository> repositories, MavenTransport transport, PgpVerifier pgpVerifier, Path stagingRoot) {
        this(repositories, transport, pgpVerifier, stagingRoot, defaultPolicyLookup());
    }

    public Resolver(List<Repository> repositories,
                    MavenTransport transport,
                    PgpVerifier pgpVerifier,
                    Path stagingRoot,
                    PgpPolicyLookup pgpPolicyLookup) {
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(pgpVerifier, "pgpVerifier");
        Objects.requireNonNull(stagingRoot, "stagingRoot");
        Objects.requireNonNull(pgpPolicyLookup, "pgpPolicyLookup");
        this.repositories = List.copyOf(repositories);
        this.versionResolver = new VersionResolver(transport, new MavenMetadataParser());
        this.downloader = new ArtifactDownloader(transport);
        this.pgpVerifier = pgpVerifier;
        this.stagingRoot = stagingRoot;
        this.pgpPolicyLookup = pgpPolicyLookup;
    }

    public List<Repository> repositories() {
        return repositories;
    }

    /**
     * Resolves {@code coord} against the repository list with no credentials.
     * Convenience overload — 12.1 callers have no CredentialsStore yet.
     */
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
        Path stagingDir;
        try {
            Files.createDirectories(stagingRoot);
            stagingDir = Files.createTempDirectory(stagingRoot, FRESH_STAGING_PREFIX);
        } catch (IOException e) {
            log.warn("failed to create staging dir under {}", stagingRoot, e);
            return new ResolveOutcome.TransportFailure(coord, repository, STAGE_METADATA,
                    new TransportResult.Network(stagingRoot.toString(), e));
        }

        VersionResolver.Result vr = versionResolver.resolve(coord, repository, stagingDir, credentials);
        return switch (vr) {
            case VersionResolver.Result.Resolved r -> downloadJar(coord.groupId(), coord.artifactId(),
                    r.version(), repository, credentials, stagingDir);
            case VersionResolver.Result.NoMetadata noMd ->
                    new ResolveOutcome.NotFound(coord, "no metadata at " + noMd.url());
            case VersionResolver.Result.NoVersionsListed nvl ->
                    new ResolveOutcome.NotFound(coord, "metadata for " + nvl.metadata().artifactId() + " lists no versions");
            case VersionResolver.Result.MetadataMalformed bad ->
                    new ResolveOutcome.NotFound(coord, "malformed metadata: " + bad.cause().getMessage());
            case VersionResolver.Result.TransportFailed tf ->
                    new ResolveOutcome.TransportFailure(coord, repository, STAGE_METADATA, tf.cause());
        };
    }

    private ResolveOutcome downloadJar(String groupId, String artifactId, String version, Repository repository,
                                       Optional<Credentials> credentials, Path stagingDir) {
        MavenCoord coord = MavenCoord.of(groupId, artifactId, version);

        TransportResult jarFetch = downloader.fetchJar(coord, version, repository, stagingDir, credentials);
        Path jarPath = okPath(jarFetch);
        if (jarPath == null) {
            return jarTransportToOutcome(coord, repository, STAGE_JAR, jarFetch);
        }

        ResolveOutcome checksumOutcome = verifyChecksum(coord, version, repository, credentials, stagingDir, jarPath);
        if (checksumOutcome != null) {
            return checksumOutcome;
        }

        return switch (pgpPolicyLookup.policyFor(repository)) {
            case PgpSignaturePolicy.Required req ->
                    resolveWithSignature(coord, version, repository, credentials, stagingDir, jarPath, req.keyRing());
            case PgpSignaturePolicy.NotRequired nr ->
                    new ResolveOutcome.Resolved(new ResolvedArtifact(
                            coord, repository, jarPath, Optional.empty(), readDigestUnchecked(jarPath)));
        };
    }

    private ResolveOutcome resolveWithSignature(MavenCoord coord, String version, Repository repository,
                                                Optional<Credentials> credentials, Path stagingDir, Path jarPath,
                                                KeyRing keyRing) {
        ResolveOutcome sigOutcome = verifySignature(coord, version, repository, credentials, stagingDir, jarPath, keyRing);
        if (sigOutcome != null) {
            return sigOutcome;
        }
        return new ResolveOutcome.Resolved(new ResolvedArtifact(
                coord, repository, jarPath,
                Optional.of(stagingDir.resolve(coord.jarFileName(version) + ".asc")),
                readDigestUnchecked(jarPath)));
    }

    private ResolveOutcome verifyChecksum(MavenCoord coord, String version, Repository repository,
                                          Optional<Credentials> credentials, Path stagingDir, Path jarFile) {
        TransportResult shaFetch = downloader.fetchSha256(coord, version, repository, stagingDir, credentials);
        Path shaPath = okPath(shaFetch);
        if (shaPath == null) {
            return jarTransportToOutcome(coord, repository, STAGE_SHA256, shaFetch);
        }

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

    private ResolveOutcome verifySignature(MavenCoord coord, String version, Repository repository,
                                           Optional<Credentials> credentials, Path stagingDir, Path jarFile,
                                           KeyRing keyRing) {
        TransportResult ascFetch = downloader.fetchAsc(coord, version, repository, stagingDir, credentials);
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
     * Extracts the local path from a successful {@link TransportResult.Ok},
     * or returns {@code null} for any failure variant. Callers fall back to
     * {@link #jarTransportToOutcome} to surface the appropriate failure.
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
     * walking the repository list. Order (most to least): ChecksumMismatch,
     * SignatureInvalid, TransportFailure, NotFound.
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

    /** Defaults the per-repository PGP policy from {@link Repository#requireSignature()}. */
    @FunctionalInterface
    public interface PgpPolicyLookup {
        PgpSignaturePolicy policyFor(Repository repository);
    }

    private static PgpPolicyLookup defaultPolicyLookup() {
        return repo -> PgpSignaturePolicy.NotRequired.INSTANCE;
    }

    /**
     * Garbage-collects staging directories older than {@code ageThreshold}.
     * Called by long-lived processes to keep the staging root tidy; tests
     * trigger this explicitly.
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
