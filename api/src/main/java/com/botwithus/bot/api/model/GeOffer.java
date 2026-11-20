package com.botwithus.bot.api.model;

/**
 * One Grand Exchange slot as the client holds it ({@code get_ge_offers}).
 *
 * <p><b>Predicate: {@link #isActive()}</b>, which is {@code rawStage != 0}. An empty slot's
 * other fields are whatever the client left there (item 0 on the main market), so read
 * nothing else off a slot that is not active.</p>
 *
 * <p>The agent has read only empty slots live so far; the field meanings of a slot holding
 * a live offer come from the client's layout and are not yet confirmed against one.</p>
 *
 * @param slot         0..7
 * @param rawStage     the client's stage code; see {@link #stage()}
 * @param stageName    the agent's spelling of the stage
 * @param rawType      0 buy, 1 sell; see {@link #type()}
 * @param typeName     the agent's spelling of the type
 * @param itemId       the offer's item
 * @param itemCount    how many items the offer is for
 * @param soldCount    how many have been bought or sold so far
 * @param price        coins per item; can exceed 32 bits
 * @param goldFromSale coins moved so far; can exceed 32 bits
 */
public record GeOffer(int slot, int rawStage, String stageName, int rawType, String typeName,
                      int itemId, int itemCount, int soldCount, long price, long goldFromSale) {

    /** True when the slot holds an offer. */
    public boolean isActive() {
        return rawStage != GeOfferStage.EMPTY.wireCode();
    }

    /** The decoded stage; {@link GeOfferStage#UNKNOWN} for a code this host does not list. */
    public GeOfferStage stage() {
        return GeOfferStage.fromWireCode(rawStage);
    }

    /** The decoded type; {@link GeOfferType#UNKNOWN} for a code this host does not list. */
    public GeOfferType type() {
        return GeOfferType.fromWireCode(rawType);
    }
}
