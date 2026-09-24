package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.model.VarpState;

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
 * <h2>Set, default, missing, unknown</h2>
 * <p>Varps are set lazily by the server, so a varp at its default usually has no entry in
 * the client. {@link #readVarp(int)} says which of four things is true, as a
 * {@link VarpState}: the varp is {@code SET}; it exists but is at its default
 * ({@code DEFAULT_NOT_SET_CLIENTSIDE}); there is no such varp ({@code NO_SUCH_VARP}); or the
 * read could not be made ({@code UNAVAILABLE}: not in game, still entering the world, a
 * timed-out read). <b>Use the state, never the value, to tell these apart.</b> A set
 * object-typed varp legitimately holds {@code -1}, and so does such a varp at its
 * default.</p>
 *
 * <p>The plain {@code int} accessors ({@link #getVarp(int)}, {@link #getVarps(List)},
 * {@link #getVarbit(int)}, {@link #queryVarbits(List)}) return the same value the
 * {@code read*} methods do: what the game itself would read when that is knowable (the
 * stored value, or the type default from the cache definition), and {@code -1} for a missing
 * varp or a failed read.</p>
 *
 * <p>Varbit values are decoded consumer-side from the base variable and the varbit's bit
 * range in the cache config. A varbit takes its base's state. Over a base at its default, it
 * decodes the base's <em>default</em>, as the game does: for a varp whose default is
 * {@code -1}, every bit of the range reads as set.</p>
 *
 * <p>The varc accessors are raw: the agent reports no per-id state for client variables, so
 * an unset varc reads {@code -1}.</p>
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface VariableAPI {

    /**
     * Returns the value of a player variable (varp): what the game reads for it.
     *
     * <p>For a {@link com.botwithus.bot.api.model.VarKind#LONG LONG} varp this is the low
     * 32 bits; use {@link #getVarpLong(int)} for the whole value. To learn whether the varp
     * is set rather than at its default, use {@link #readVarp(int)}.</p>
     *
     * @param varId the variable ID
     * @return the stored value, or the type default when the client holds no entry, or
     *         {@code -1} when there is no such varp or the read could not be made
     */
    int getVarp(int varId);

    /**
     * The full 64-bit value of a player variable, for {@link
     * com.botwithus.bot.api.model.VarKind#LONG LONG} varps whose value does not fit an
     * {@code int}. For any other varp it equals {@link #getVarp(int)}, sign-extended.
     *
     * @param varId the variable ID
     * @return as {@link #getVarp(int)}, at full width
     */
    long getVarpLong(int varId);

    /**
     * Reads a varp with its {@link VarpState state}.
     *
     * @param varId the variable ID
     * @return the read; never {@code null}
     */
    VarpRead readVarp(int varId);

    /**
     * Batch form of {@link #readVarp(int)}: one read per input id, in order. Any number of
     * ids may be passed; the agent's per-request cap is handled here.
     *
     * @param varIds the variable IDs
     * @return one read per id, in order
     */
    List<VarpRead> readVarps(List<Integer> varIds);

    /**
     * Just the state of a varp: whether it is set, at its default, missing, or unknown.
     *
     * @param varId the variable ID
     * @return the state
     */
    default VarpState varpState(int varId) {
        return readVarp(varId).state();
    }

    /**
     * Reads a varbit with the state of its base variable.
     *
     * @param varbitId the varbit ID
     * @return the read; never {@code null}
     */
    VarbitRead readVarbit(int varbitId);

    /**
     * Batch form of {@link #readVarbit(int)}: one read per input id, in order.
     *
     * @param varbitIds the varbit IDs
     * @return one read per id, in order
     */
    List<VarbitRead> readVarbits(List<Integer> varbitIds);

    /**
     * Returns the value of a variable bit (varbit), decoded from its base
     * variable and bit range.
     *
     * <p>A base variable the client holds no entry for decodes its type default, the same
     * answer the engine's own bit extractor gives: {@code 0} for most varps, so a
     * single-bit unlock flag reads "locked" on an account that never set it; every bit
     * set for a varp whose default is {@code -1}. When the cache cannot supply the
     * default, {@code 0} is used; {@link #readVarbit(int)} reports that case.</p>
     *
     * <p>A base the agent could not read at all (not in game, a timed-out read) is
     * <em>not</em> unset: nothing is known about it, so it reads {@code -1}, never a
     * cleared {@code 0}. Code that tracks a value over time should keep its last known
     * value on {@code -1}.</p>
     *
     * @param varbitId the varbit ID
     * @return the decoded varbit value; the decoded default when the varbit's base
     *         variable is unset, or {@code -1} when the varbit id or its base is
     *         unknown to the cache (or its bit range is malformed) or its base could
     *         not be read
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
     * @return one {@link VarbitValue} per input id, in order, each valued as
     *         {@link #getVarbit(int)} would value it
     */
    List<VarbitValue> queryVarbits(List<Integer> varbitIds);

    /**
     * Batch counterpart of {@link #getVarp(int)}. One pipe round-trip and one
     * game-thread visit for the whole batch, instead of one per id.
     *
     * @param varIds the varp IDs to read
     * @return one value per input id, in order, each valued as {@link #getVarp(int)}
     *         would value it
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
