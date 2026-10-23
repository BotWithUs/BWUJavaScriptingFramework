package com.botwithus.bot.api.model;

/** How the client stores a set varp's value. Only meaningful for {@link VarpState#SET}. */
public enum VarKind {
    /** A 32-bit integer. */
    INT,
    /**
     * A 64-bit integer. {@link VarpRead#value()} holds only the low 32 bits;
     * {@link VarpRead#value64()} holds the whole value.
     */
    LONG,
    /** A string. The integer values carry no meaning for it. */
    STRING,
    /** Not reported: the varp is not set, or the agent predates kinds. */
    UNKNOWN
}
