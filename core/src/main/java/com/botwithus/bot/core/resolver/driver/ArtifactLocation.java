package com.botwithus.bot.core.resolver.driver;

import java.net.URI;
import java.util.Objects;

/**
 * Where a repository driver thinks an artifact piece (jar, checksum,
 * signature) lives. Sealed because the result set is closed — the
 * resolver either has a URL to fetch from, or knows the driver has
 * decided the piece is unavailable.
 *
 * <p>{@link Missing} is used both for "this repository layout doesn't
 * publish this piece type" (e.g., flat HTTP directory listings with no
 * checksum sidecar) and for "the driver could compute a URL but knows
 * it won't exist" (e.g., a snapshot pinned to a non-snapshot repo).</p>
 */
public sealed interface ArtifactLocation permits ArtifactLocation.Url, ArtifactLocation.Missing {

    /** The driver located the piece at {@link #uri}. The resolver fetches via the transport. */
    record Url(URI uri) implements ArtifactLocation {
        public Url {
            Objects.requireNonNull(uri, "uri");
        }
    }

    /**
     * The driver has decided this piece is unavailable for the given
     * coordinate and repository. {@link #reason} is a human-readable
     * diagnostic used in error rendering only.
     */
    record Missing(String reason) implements ArtifactLocation {
        public Missing {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
