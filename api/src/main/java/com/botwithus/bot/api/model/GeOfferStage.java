package com.botwithus.bot.api.model;

/**
 * The stage of one Grand Exchange slot, as the client's own enum numbers it
 * ({@code get_ge_offers} {@code stage}).
 */
public enum GeOfferStage {
    /** No offer in the slot. The slot's other fields are left over and mean nothing. */
    EMPTY(0),
    ADDING(1),
    ADDED(2),
    VERIFYING(3),
    ABORTING(4),
    FINISHED(5),
    /** A stage code this host does not list; {@link GeOffer#rawStage()} still carries it. */
    UNKNOWN(-1);

    private final int wireCode;

    GeOfferStage(int wireCode) {
        this.wireCode = wireCode;
    }

    /** The agent's {@code stage} code; {@code -1} for {@link #UNKNOWN}. */
    public int wireCode() {
        return wireCode;
    }

    /** The stage for an agent {@code stage} code; {@link #UNKNOWN} for anything unlisted. */
    public static GeOfferStage fromWireCode(int code) {
        for (GeOfferStage stage : values()) {
            if (stage != UNKNOWN && stage.wireCode == code) {
                return stage;
            }
        }
        return UNKNOWN;
    }
}
