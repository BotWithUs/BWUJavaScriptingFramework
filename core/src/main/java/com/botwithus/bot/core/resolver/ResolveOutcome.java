package com.botwithus.bot.core.resolver;

import com.botwithus.bot.core.resolver.pgp.SignatureResult;
import com.botwithus.bot.core.resolver.transport.TransportResult;

/**
 * Result of attempting to resolve a single {@link MavenCoord} against a
 * configured repository list. Consumers must exhaustively switch on the
 * permitted subtypes.
 */
public sealed interface ResolveOutcome
        permits ResolveOutcome.Resolved,
                ResolveOutcome.NotFound,
                ResolveOutcome.ChecksumMismatch,
                ResolveOutcome.SignatureInvalid,
                ResolveOutcome.TransportFailure {

    /** Artifact found, downloaded, checksum and (if required) signature verified. */
    record Resolved(ResolvedArtifact artifact) implements ResolveOutcome {}

    /**
     * No repository in the search list contained this coordinate. The
     * {@code reason} string is suitable for human display.
     */
    record NotFound(MavenCoord coord, String reason) implements ResolveOutcome {}

    /**
     * The artifact was downloaded but the SHA-256 digest did not match the
     * expected value published alongside it.
     */
    record ChecksumMismatch(MavenCoord coord, Repository repository,
                            byte[] expected, byte[] actual) implements ResolveOutcome {}

    /**
     * The artifact was downloaded but the detached PGP signature did not
     * verify against the configured keyring. Inspect {@link SignatureResult}
     * for the precise failure mode.
     */
    record SignatureInvalid(MavenCoord coord, Repository repository,
                            SignatureResult signatureResult) implements ResolveOutcome {}

    /**
     * Network or filesystem transport failed for one of the artifact pieces
     * (metadata, JAR, checksum, signature). Carries the underlying
     * {@link TransportResult} for diagnostic display.
     */
    record TransportFailure(MavenCoord coord, Repository repository,
                            String stage, TransportResult cause) implements ResolveOutcome {}
}
