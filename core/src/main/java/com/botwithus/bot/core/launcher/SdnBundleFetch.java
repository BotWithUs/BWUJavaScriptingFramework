package com.botwithus.bot.core.launcher;

/** How fetching a Store bundle over the launcher's pipe ended. */
public sealed interface SdnBundleFetch {

    /**
     * Every chunk arrived and the whole matched the offer's length and digest.
     *
     * @param bundle the bundle, in the same form a file delivery carries
     */
    record Fetched(byte[] bundle) implements SdnBundleFetch {

        public Fetched {
            bundle = bundle.clone();
        }

        /** @return a copy of the bundle */
        @Override
        public byte[] bundle() {
            return bundle.clone();
        }
    }

    /** The service answered {@code sdn_not_delivered}. It never says why, and neither does this. */
    record NotDelivered() implements SdnBundleFetch {
    }

    /**
     * No service answered: none is running, or the connection broke mid-delivery.
     *
     * @param code {@code service_unavailable} or {@code service_stopped}
     */
    record Unavailable(String code) implements SdnBundleFetch {
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
