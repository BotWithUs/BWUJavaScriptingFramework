package com.botwithus.bot.core.resolver;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A successfully fetched-and-verified artifact ready for install. The
 * {@code jar} path points at the resolver's staging directory; the
 * installer is responsible for moving it into {@code scripts/}.
 */
public record ResolvedArtifact(
        MavenCoord coord,
        Repository repository,
        Path jar,
        Optional<Path> asc,
        byte[] sha256) {

    public ResolvedArtifact {
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(asc, "asc");
        Objects.requireNonNull(sha256, "sha256");
        if (coord.version().isEmpty()) {
            throw new IllegalArgumentException("ResolvedArtifact requires a concrete version");
        }
        if (sha256.length != SHA256_LENGTH) {
            throw new IllegalArgumentException("sha256 must be " + SHA256_LENGTH + " bytes");
        }
    }

    /** SHA-256 digest length in bytes. */
    public static final int SHA256_LENGTH = 32;

    public String resolvedVersion() {
        return coord.version().orElseThrow();
    }
}
