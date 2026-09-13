package com.botwithus.bot.core.sdn;

import java.util.List;

/**
 * The outcome of asking the launcher for the script catalogue.
 *
 * <p>Every failure here has a different thing the user can do about it, so they
 * are distinct variants rather than an empty list and a log line. A caller that
 * switches over this type gets told by the compiler when a new one appears.
 */
public sealed interface SdnCatalogueResult {

    /**
     * The catalogue arrived. {@code stale} marks a copy read from the last
     * answer on disk because the launcher did not respond this time — the
     * contents are real but may have moved on.
     */
    record Delivered(List<SdnCatalogueEntry> entries, boolean stale)
            implements SdnCatalogueResult {

        public Delivered {
            entries = List.copyOf(entries);
        }
    }

    /** The launcher did not answer, and there was no previous answer to fall back on. */
    record CourierUnavailable() implements SdnCatalogueResult {
    }

    /** The launcher is running but nobody is signed in to a BotWithUs account. */
    record NotSignedIn() implements SdnCatalogueResult {
    }

    /**
     * The account is signed in but has no active BotWithUs subscription. This is
     * separate from subscribing to individual scripts: owning script
     * subscriptions is not enough to read the catalogue.
     */
    record SubscriptionRequired() implements SdnCatalogueResult {
    }

    /** Anything else the launcher reported, or a reply that could not be read. */
    record Failed(String reason) implements SdnCatalogueResult {
    }
}
