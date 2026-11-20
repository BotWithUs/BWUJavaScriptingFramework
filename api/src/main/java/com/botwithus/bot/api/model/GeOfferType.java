package com.botwithus.bot.api.model;

/** Whether a Grand Exchange offer buys or sells ({@code get_ge_offers} {@code type}). */
public enum GeOfferType {
    BUY(0),
    SELL(1),
    /** A type code this host does not list; {@link GeOffer#rawType()} still carries it. */
    UNKNOWN(-1);

    private final int wireCode;

    GeOfferType(int wireCode) {
        this.wireCode = wireCode;
    }

    /** The agent's {@code type} code; {@code -1} for {@link #UNKNOWN}. */
    public int wireCode() {
        return wireCode;
    }

    /** The type for an agent {@code type} code; {@link #UNKNOWN} for anything unlisted. */
    public static GeOfferType fromWireCode(int code) {
        for (GeOfferType type : values()) {
            if (type != UNKNOWN && type.wireCode == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
