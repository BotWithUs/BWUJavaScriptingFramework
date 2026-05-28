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
 * config from the cache.</p>
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
     * @param varbitId the varbit ID
     * @return the varbit value, or {@code -1} if the varbit is unknown
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
     * Batch-resolves multiple varbit values.
     *
     * @param varbitIds the varbit IDs to query
     * @return one {@link VarbitValue} per input id, in order
     */
    List<VarbitValue> queryVarbits(List<Integer> varbitIds);
}
