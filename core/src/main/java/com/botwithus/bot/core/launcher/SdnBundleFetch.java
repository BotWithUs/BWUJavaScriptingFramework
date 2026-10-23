package com.botwithus.bot.core.launcher;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** How fetching a Store bundle over the launcher's pipe ended. */
public sealed interface SdnBundleFetch {

    /**
     * Every chunk arrived and the whole matched the offer's length and digest.
     *
     * @param bundle    the bundle, in the same form a file delivery carries
     * @param delivered the catalogue id of each jar, in jar order, already checked
     *                  against the request; empty from a launcher that predates it
     * @param failed    the requested scripts the launcher could not deliver
     */
    record Fetched(byte[] bundle, Optional<List<String>> delivered, List<SdnScriptFailure> failed)
            implements SdnBundleFetch {

        public Fetched {
            bundle = bundle.clone();
            delivered = Objects.requireNonNull(delivered, "delivered").map(List::copyOf);
            failed = List.copyOf(failed);
        }

        /** A whole delivery from a launcher that says nothing of which jar is which. */
        public Fetched(byte[] bundle) {
            this(bundle, Optional.empty(), List.of());
        }

        /** @return a copy of the bundle */
        @Override
        public byte[] bundle() {
            return bundle.clone();
        }
    }

    /**
     * The service answered {@code sdn_not_delivered}.
     *
     * @param refusal why, as far as the launcher said; nothing from an older one
     */
    record NotDelivered(SdnRefusal refusal) implements SdnBundleFetch {
    }

    /**
     * No service answered.
     *
     * @param isTimedOut whether one was connected but did not answer in time, rather
     *                   than none being connected
     */
    record Unavailable(boolean isTimedOut) implements SdnBundleFetch {
    }

    /**
     * Anything else: another error code from the service, or a bundle this host
     * refused because it did not match what the service offered.
     *
     * @param reason the service's error code, or this host's own short account;
     *               never text the service sent
     */
    record Failed(String reason) implements SdnBundleFetch {
    }
}
