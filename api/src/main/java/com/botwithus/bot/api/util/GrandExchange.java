package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.GrandExchangeOffer;

import java.util.List;

/**
 * Convenience wrapper around the Grand Exchange offer system.
 */
public final class GrandExchange {

    /** GE offer status: empty/unused slot. */
    public static final int STATUS_EMPTY = 0;
    /** GE offer status: buy offer in progress. */
    public static final int STATUS_BUYING = 2;
    /** GE offer status: sell offer in progress. */
    public static final int STATUS_SELLING = 3;
    /** GE offer status: buy offer completed. */
    public static final int STATUS_BUY_COMPLETE = 5;
    /** GE offer status: sell offer completed. */
    public static final int STATUS_SELL_COMPLETE = 6;

    private final GameAPI api;

    public GrandExchange(GameAPI api) {
        this.api = api;
    }

    /**
     * Checks whether the Grand Exchange interface is currently open.
     *
     * @return {@code true} if the GE interface is open
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(Interfaces.GRAND_EXCHANGE);
    }

    /**
     * Returns all GE offer slots.
     *
     * @return the list of offers
     */
    public List<GrandExchangeOffer> getOffers() {
        return api.getGrandExchangeOffers();
    }

    /**
     * Finds the first offer matching the given item ID, or {@code null} if none.
     *
     * @param itemId the item ID to search for
     * @return the matching offer, or {@code null}
     */
    public GrandExchangeOffer findOffer(int itemId) {
        for (GrandExchangeOffer offer : getOffers()) {
            if (offer.itemId() == itemId && offer.status() != STATUS_EMPTY) {
                return offer;
            }
        }
        return null;
    }

    /**
     * Checks if there is at least one empty GE slot.
     *
     * @return {@code true} if a free slot exists
     */
    public boolean hasFreeSlot() {
        for (GrandExchangeOffer offer : getOffers()) {
            if (offer.status() == STATUS_EMPTY) return true;
        }
        return false;
    }

    /**
     * Checks whether all active offers have completed.
     *
     * @return {@code true} if every non-empty slot is in a completed state
     */
    public boolean allCompleted() {
        for (GrandExchangeOffer offer : getOffers()) {
            int s = offer.status();
            if (s != STATUS_EMPTY && s != STATUS_BUY_COMPLETE && s != STATUS_SELL_COMPLETE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the remaining quantity for an offer (total requested minus completed).
     *
     * @param offer the GE offer
     * @return the remaining quantity
     */
    public static int getRemainingQuantity(GrandExchangeOffer offer) {
        return offer.count() - offer.completedCount();
    }

    /**
     * Returns the completion fraction of an offer as a value in [0.0, 1.0].
     *
     * @param offer the GE offer
     * @return the completion fraction
     */
    public static double getCompletionFraction(GrandExchangeOffer offer) {
        if (offer.count() <= 0) return 0.0;
        return (double) offer.completedCount() / offer.count();
    }
}
