package com.botwithus.bot.core.resolver.driver;

import java.util.List;
import java.util.Optional;

/**
 * Parsed {@code maven-metadata.xml} for a single {@code groupId:artifactId}
 * directory in a repository. Package-private driver implementation detail
 * — public callers consume a driver-neutral {@link VersionListing}.
 *
 * <p>{@link #release} is the {@code <release>} tag (typically the most
 * recent non-snapshot version); {@link #latest} is {@code <latest>}
 * (which may be a snapshot). Both may be absent for partially-populated
 * repositories — callers fall back to the last entry in {@link #versions}.</p>
 */
record MavenMetadata(
        String groupId,
        String artifactId,
        Optional<String> latest,
        Optional<String> release,
        List<String> versions) {

    MavenMetadata {
        versions = List.copyOf(versions);
    }

    Optional<String> bestRelease() {
        if (release.isPresent()) {
            return release;
        }
        if (latest.isPresent()) {
            return latest;
        }
        if (!versions.isEmpty()) {
            return Optional.of(versions.get(versions.size() - 1));
        }
        return Optional.empty();
    }
}
