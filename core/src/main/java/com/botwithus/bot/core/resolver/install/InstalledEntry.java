package com.botwithus.bot.core.resolver.install;

import com.botwithus.bot.core.resolver.MavenCoord;

import java.time.Instant;
import java.util.Objects;

/**
 * Sidecar-index entry for one installed script JAR.
 *
 * <p>{@link #jarFilename} is just the filename inside the scripts directory
 * (not an absolute path) so the index file is portable between machines.
 * {@link #sha256Hex} is the JAR digest at install time — re-verified on
 * launch to catch tampering or partial writes.</p>
 */
public record InstalledEntry(
        String jarFilename,
        MavenCoord coord,
        Instant installedAt,
        String sha256Hex,
        String repoId) {

    public InstalledEntry {
        Objects.requireNonNull(jarFilename, "jarFilename");
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(installedAt, "installedAt");
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        Objects.requireNonNull(repoId, "repoId");
        if (coord.version().isEmpty()) {
            throw new IllegalArgumentException("InstalledEntry coord requires a version");
        }
    }

    /** Convenience: returns {@code groupId:artifactId} as the index key. */
    public String key() {
        return coord.ga();
    }

    public String version() {
        return coord.version().orElseThrow();
    }
}
