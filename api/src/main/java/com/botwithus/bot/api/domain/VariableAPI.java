package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.VarbitValue;

import java.util.List;

/**
 * Game variable access: varps (player variables), varbits (bit-ranges of a
 * base variable), and varcs (client variables, int- and string-valued).
 *
 * <p>These are on-demand reads of the live client state — each call is a pipe
 * round-trip to the producer, which walks the relevant variable hashmap on the
 * game thread. To <em>observe</em> changes instead of polling, subscribe to
 * {@code VarChangeEvent} / {@code VarbitChangeEvent} / {@code VarcChangeEvent}
 * on the {@code EventBus}.</p>
 *
 * <p>Varbit values are decoded consumer-side: the producer returns the raw base
 * variable, and {@link #getVarbit(int)} shifts/masks it using the varbit type
 * config from the cache. A base variable the game has never set has no node in
 * its domain, and the engine's own lookup treats that as <em>zero</em> rather
 * than as an error — so a varbit over an unset base reads {@code 0}, not
 * {@code -1}. The two sentinels are not interchangeable: {@code -1} from a
 * varbit read means the <em>id</em> is unknown, and {@code 0} means the varbit
 * is known and currently clear.</p>
 *
 * <p>The raw varp/varc accessors keep their own, different convention: they
 * report an unset variable as {@code -1}, because a raw read has no bit range
 * to interpret and no separate "unknown id" case to distinguish.</p>
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface VariableAPI {

    /**
     * Returns the value of a player variable (varp).
     *
     * @param varId the variable ID
     * @return the variable value, or {@code -1} if not in-game / unset
     */
    int getVarp(int varId);

    /**
     * Returns the value of a variable bit (varbit), decoded from its base
     * variable and bit range.
     *
     * <p>Fails closed on a base variable the client has not set: that decodes
     * to {@code 0}, the same answer the engine's own bit extractor gives for a
     * defaulted node. A single-bit unlock flag therefore reads "locked" on an
     * account that has never set it, rather than inheriting the bits of an
     * unset-variable placeholder.</p>
     *
     * @param varbitId the varbit ID
     * @return the decoded varbit value; {@code 0} when the varbit's base
     *         variable is unset, or {@code -1} when the varbit id is unknown
     *         to the cache (or its bit range is malformed)
     */
    int getVarbit(int varbitId);

    /**
     * Returns the value of an integer client variable (varc).
     *
     * @param varcId the varc ID
     * @return the varc value, or {@code -1} if not set
     */
    int getVarcInt(int varcId);

    /**
     * Returns the value of a string client variable (varc).
     *
     * @param varcId the varc ID
     * @return the varc string value, or the empty string if not set
     */
    String getVarcString(int varcId);

    /**
     * Batch-resolves multiple varbit values. Each value carries the same
     * contract as {@link #getVarbit(int)}, and the two agree for every id.
     *
     * @param varbitIds the varbit IDs to query
     * @return one {@link VarbitValue} per input id, in order; each value is the
     *         decoded bits, {@code 0} when that varbit's base variable is
     *         unset, or {@code -1} when the varbit id is unknown to the cache
     */
    List<VarbitValue> queryVarbits(List<Integer> varbitIds);

    /**
     * Batch counterpart of {@link #getVarp(int)}. One pipe round-trip and one
     * game-thread visit for the whole batch, instead of one per id.
     *
     * @param varIds the varp IDs to read
     * @return one value per input id, in order; unset entries are {@code -1}
     */
    List<Integer> getVarps(List<Integer> varIds);

    /**
     * Batch counterpart of {@link #getVarcInt(int)}.
     *
     * @param varcIds the varc IDs to read
     * @return one value per input id, in order; unset entries are {@code -1}
     */
    List<Integer> getVarcInts(List<Integer> varcIds);

    /**
     * Batch counterpart of {@link #getVarcString(int)}.
     *
     * @param varcIds the varc IDs to read
     * @return one string per input id, in order; unset entries are empty
     */
    List<String> getVarcStrings(List<Integer> varcIds);
}
