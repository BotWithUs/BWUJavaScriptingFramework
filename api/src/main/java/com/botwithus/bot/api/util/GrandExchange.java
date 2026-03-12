package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.GrandExchangeOffer;

import java.util.List;

/**
 * Convenience wrapper around the Grand Exchange offer system.
 *
 * @see GameAPI#getGrandExchangeOffers()
 */
public final class GrandExchange {

    /** Offer slot is empty. */
    public static final int STATUS_EMPTY = 0;
    /** Offer is actively buying. */
    public static final int STATUS_BUYING = 2;
    /** Offer is actively selling. */
    public static final int STATUS_SELLING = 3;
    /** Buy offer has completed. */
    public static final int STATUS_BUY_COMPLETE = 5;
    /** Sell offer has completed. */
    public static final int STATUS_SELL_COMPLETE = 6;

    private final GameAPI api;

    /**
     * Creates a new Grand Exchange wrapper.
     *
     * @param api the game API instance
     */
    public GrandExchange(GameAPI api) {
        this.api = api;
    }

    /**
     * Checks whether the Grand Exchange interface is open.
     *
     * @return {@code true} if the interface is open
     */
    public boolean isOpen() {
        return api.isInterfaceOpen(Interfaces.GRAND_EXCHANGE);
    }

    /**
     * Returns all Grand Exchange offer slots.
     *
     * @return a list of offers
     */
    public List<GrandExchangeOffer> getOffers() {
        return api.getGrandExchangeOffers();
    }

    /**
     * Returns the first non-empty offer matching the given item ID.
     *
     * @param itemId the item ID to search for
     * @return the matching offer, or {@code null} if none found
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
     * Checks whether there is at least one empty offer slot.
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
     * Checks whether every non-empty slot is in a completed state.
     *
     * @return {@code true} if all active offers have completed
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
     * Returns the remaining quantity for an offer (total minus completed).
     *
     * @param offer the Grand Exchange offer
     * @return the remaining quantity
     */
    public static int getRemainingQuantity(GrandExchangeOffer offer) {
        return offer.count() - offer.completedCount();
    }

    /**
     * Returns the completion fraction for an offer as a value in [0.0, 1.0].
     *
     * @param offer the Grand Exchange offer
     * @return the completion fraction
     */
    public static double getCompletionFraction(GrandExchangeOffer offer) {
        if (offer.count() <= 0) return 0.0;
        return (double) offer.completedCount() / offer.count();
    }
}
