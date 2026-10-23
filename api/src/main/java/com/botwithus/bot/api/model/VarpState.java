package com.botwithus.bot.api.model;

/**
 * What a varp read found out about the variable.
 *
 * <p>Varps are set lazily by the server, so a varp at its default usually has no client
 * entry at all. That is a different answer from "no such varp" and from "the read failed",
 * and a value alone cannot tell them apart: an object-typed varp legitimately reads
 * {@code -1} whether it is set or defaulted.</p>
 */
public enum VarpState {

    /** The client holds a value for the varp; {@link VarpRead#value()} is it. */
    SET,

    /**
     * The varp exists but the client holds no entry for it, so it is at its type default.
     * The value reported is that default, which is exactly what the game itself reads.
     * {@link VarpRead#defaultVerified()} says whether the default came from the cache
     * definition or is the plain fallback {@code 0}.
     */
    DEFAULT_NOT_SET_CLIENTSIDE,

    /** The cache has no varp with this id. The value is {@code -1}. */
    NO_SUCH_VARP,

    /**
     * The read could not be made: not in game, still entering the world, an invalid id,
     * or a timed-out read. Nothing is known about the varp. The value is {@code -1}, and
     * code that tracks a value over time should keep its last known one.
     */
    UNAVAILABLE
}
