package com.botwithus.bot.core.cache;

import java.util.Objects;

/**
 * What {@code nxt_gameval_index_version} said about a cache's gameval index
 * (cache index 67). Three outcomes that callers must treat differently, so a
 * sealed type rather than an {@code int} with a sentinel: the version itself
 * can be any {@code int}, negative values included.
 */
public sealed interface GamevalIndexVersion {

    /**
     * The cache carries a gameval index at this reference-table version.
     *
     * @param value the exact value a build from this cache writes as {@code meta.index_version}
     */
    record Present(int value) implements GamevalIndexVersion {

        /**
         * The value as {@code meta.index_version} stores it: a decimal string
         * with no padding, {@code "-12"} or {@code "1729"}.
         */
        public String metaValue() {
            return Integer.toString(value);
        }
    }

    /** The cache has no gameval index: no {@code js5-67.jcache}, an empty one, or a pre-951 cache. */
    record Absent() implements GamevalIndexVersion {
    }

    /**
     * The probe failed for another reason.
     *
     * @param resultCode the library's {@code nxt_result}
     * @param reason     {@code nxt_last_error()} at the time of the failure
     */
    record Failed(int resultCode, String reason) implements GamevalIndexVersion {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
