package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.GrandExchangeOffer;

import java.util.List;

/**
 * Convenience wrapper around the Grand Exchange offer system.
 */
public final class GrandExchange {

    public static final int STATUS_EMPTY = 0;
    public static final int STATUS_BUYING = 2;
    public static final int STATUS_SELLING = 3;
    public static final int STATUS_BUY_COMPLETE = 5;
    public static final int STATUS_SELL_COMPLETE = 6;

    private final GameAPI api;

    public GrandExchange(GameAPI api) {
        this.api = api;
    }

    public boolean isOpen() {
        return api.isInterfaceOpen(Interfaces.GRAND_EXCHANGE);
    }

    public List<GrandExchangeOffer> getOffers() {
        return api.getGrandExchangeOffers();
    }

    /** Returns the first matching offer, or {@code null} if none. */
    public GrandExchangeOffer findOffer(int itemId) {
        for (GrandExchangeOffer offer : getOffers()) {
            if (offer.itemId() == itemId && offer.status() != STATUS_EMPTY) {
                return offer;
            }
        }
        return null;
    }

    public boolean hasFreeSlot() {
        for (GrandExchangeOffer offer : getOffers()) {
            if (offer.status() == STATUS_EMPTY) return true;
        }
        return false;
    }

    /** True if every non-empty slot is in a completed state. */
    public boolean allCompleted() {
        for (GrandExchangeOffer offer : getOffers()) {
            int s = offer.status();
            if (s != STATUS_EMPTY && s != STATUS_BUY_COMPLETE && s != STATUS_SELL_COMPLETE) {
                return false;
            }
        }
        return true;
    }

    public static int getRemainingQuantity(GrandExchangeOffer offer) {
        return offer.count() - offer.completedCount();
    }

    /** Returns a value in [0.0, 1.0]. */
    public static double getCompletionFraction(GrandExchangeOffer offer) {
        if (offer.count() <= 0) return 0.0;
        return (double) offer.completedCount() / offer.count();
    }
}
