package com.botwithus.bot.core.resolver.driver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Driver-independent view of "what versions does this repository know
 * about for this coordinate?". Replaces the Maven-specific
 * {@code MavenMetadata} as the type at the {@code Resolver}-↔-driver
 * boundary.
 *
 * <p>{@link #bestRelease} is the driver's best-guess "newest concrete
 * version" — for Maven that's {@code <release>} ⟶ {@code <latest>} ⟶
 * last entry in versions; for a GitHub-releases driver it'd be the
 * topmost non-prerelease tag.</p>
 *
 * <p>{@link #versions} carries every version the driver knows about,
 * in driver-defined order — useful for {@code scripts info} listings
 * and for callers that want to pin a non-latest version.</p>
 */
public record VersionListing(Optional<String> bestRelease, List<String> versions) {

    public VersionListing {
        Objects.requireNonNull(bestRelease, "bestRelease");
        Objects.requireNonNull(versions, "versions");
        versions = List.copyOf(versions);
    }

    public static VersionListing empty() {
        return new VersionListing(Optional.empty(), List.of());
    }

    public static VersionListing of(String latest, List<String> versions) {
        Objects.requireNonNull(latest, "latest");
        return new VersionListing(Optional.of(latest), versions);
    }
}
