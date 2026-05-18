package com.botwithus.bot.core.resolver.driver;

import com.botwithus.bot.core.resolver.transport.TransportResult;

import java.util.Objects;

/**
 * Outcome of {@link RepositoryDriver#listVersions}. Sealed because every
 * driver's "I tried to fetch versions and got..." answer falls into one
 * of these four buckets: I got versions, the coordinate isn't in this
 * repo, I got bad data, or transport failed.
 *
 * <p>Drivers don't synthesize {@link com.botwithus.bot.core.resolver.ResolveOutcome}
 * directly — the resolver lifts these into the appropriate outcome with
 * the repository context attached.</p>
 */
public sealed interface ListVersionsResult
        permits ListVersionsResult.Ok,
                ListVersionsResult.NotIndexed,
                ListVersionsResult.Malformed,
                ListVersionsResult.TransportFailed {

    record Ok(VersionListing listing) implements ListVersionsResult {
        public Ok {
            Objects.requireNonNull(listing, "listing");
        }
    }

    record NotIndexed(String reason) implements ListVersionsResult {
        public NotIndexed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Malformed(String reason) implements ListVersionsResult {
        public Malformed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record TransportFailed(TransportResult cause) implements ListVersionsResult {
        public TransportFailed {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
