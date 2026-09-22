package com.botwithus.bot.core.cache;

/**
 * What the cache knows about one varp id, as far as a varp read needs it: whether the varp
 * exists, and if so what its client-side default is.
 *
 * <p>"The cache could not say" ({@link Unknown}) is kept apart from "the cache says there is
 * no such varp" ({@link NoSuchVarp}). A cache I/O failure must never read as a missing
 * varp.</p>
 */
public sealed interface VarpCacheInfo {

    /**
     * The varp exists and its default is known: the value the game reads when the client
     * holds no entry.
     *
     * @param defaultValue the default at full width (every current default fits an int)
     */
    record Default(long defaultValue) implements VarpCacheInfo {
    }

    /** The varp exists, but its default cannot be computed (unknown or non-integer type). */
    record ExistsDefaultUnknown() implements VarpCacheInfo {
    }

    /** The cache has no varp with this id. */
    record NoSuchVarp() implements VarpCacheInfo {
    }

    /**
     * Nothing is known: the cache is not open, its build lacks the lookup, it has not
     * finished warming up, or the lookup failed.
     */
    record Unknown() implements VarpCacheInfo {
    }
}
