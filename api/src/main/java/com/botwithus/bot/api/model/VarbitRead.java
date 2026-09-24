package com.botwithus.bot.api.model;

/**
 * One varbit read. A varbit is a bit range of a base variable, so its state is the base's
 * state: {@link VarpState#SET SET} decodes the stored base,
 * {@link VarpState#DEFAULT_NOT_SET_CLIENTSIDE DEFAULT_NOT_SET_CLIENTSIDE} decodes the
 * base's type default (which is what the game reads, and for a varp whose default is
 * {@code -1} that means every bit of the range is set), and the other two states carry
 * {@code -1}.
 *
 * <p>{@link VarpState#NO_SUCH_VARP NO_SUCH_VARP} covers both an unknown varbit id and a
 * varbit whose base varp does not exist.</p>
 *
 * @param id              the varbit id
 * @param state           the state of the varbit's base variable
 * @param value           the decoded bits, or {@code -1} when there is no value
 * @param defaultVerified as {@link VarpRead#defaultVerified()}, for the base variable
 */
public record VarbitRead(int id, VarpState state, int value, boolean defaultVerified) {

    /** True when {@link #value()} is what the game reads. */
    public boolean hasValue() {
        return state == VarpState.SET || state == VarpState.DEFAULT_NOT_SET_CLIENTSIDE;
    }
}
