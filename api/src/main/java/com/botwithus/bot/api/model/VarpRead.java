package com.botwithus.bot.api.model;

/**
 * One varp read: its {@link VarpState state}, and the value the game would read for it.
 *
 * <p>Test {@link #state()}, never the value, to learn whether a varp is set. The value is
 * the client's value when {@link VarpState#SET SET}, the type default when
 * {@link VarpState#DEFAULT_NOT_SET_CLIENTSIDE DEFAULT_NOT_SET_CLIENTSIDE}, and {@code -1}
 * otherwise. A set varp can itself hold {@code -1}.</p>
 *
 * @param id              the varp id
 * @param state           what the read found out
 * @param value           the value, low 32 bits for a {@link VarKind#LONG LONG} varp
 * @param value64         the full value, sign-extended for a 32-bit varp
 * @param kind            how a set varp is stored; {@link VarKind#UNKNOWN} otherwise
 * @param defaultVerified for {@link VarpState#DEFAULT_NOT_SET_CLIENTSIDE}, whether the
 *                        default came from the cache definition ({@code true}) or is the
 *                        fallback {@code 0} because the cache could not say
 *                        ({@code false}); {@code true} for every other state
 */
public record VarpRead(int id, VarpState state, int value, long value64, VarKind kind,
                       boolean defaultVerified) {

    /** The value reported for {@link VarpState#NO_SUCH_VARP} and {@link VarpState#UNAVAILABLE}. */
    public static final int NO_VALUE = -1;

    /** True when the client holds a value for the varp. */
    public boolean isSet() {
        return state == VarpState.SET;
    }

    /** True when {@link #value()} is what the game reads: set, or at its default. */
    public boolean hasValue() {
        return state == VarpState.SET || state == VarpState.DEFAULT_NOT_SET_CLIENTSIDE;
    }
}
