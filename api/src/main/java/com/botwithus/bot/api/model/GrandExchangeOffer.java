package com.botwithus.bot.api.model;

/**
 * An active Grand Exchange trade offer.
 *
 * <p>Restored after the Slice 3 cull. The producer-side read path that
 * materialised these records ({@code query_grand_exchange} RPC) is not yet
 * present — see {@link com.botwithus.bot.api.util.GrandExchange#getOffers()}
 * for the current read story.</p>
 *
 * @param slot           GE slot index, 0-based (0-5 standard, 0-7 with premier)
 * @param status         offer status code; see {@link com.botwithus.bot.api.util.GrandExchange}
 *                       {@code STATUS_*} constants
 * @param type           0 = buy, 1 = sell
 * @param itemId         item being traded
 * @param price          price per item in coins
 * @param count          total quantity requested
 * @param completedCount quantity fulfilled so far
 * @param completedGold  total gold transferred so far
 */
public record GrandExchangeOffer(
        int slot,
        int status,
        int type,
        int itemId,
        int price,
        int count,
        int completedCount,
        int completedGold
) {

    /** True when the slot is empty (no offer placed). */
    public boolean isEmpty() {
        return status == 0;
    }

    /** Remaining quantity = total requested minus what's been transferred. */
    public int remaining() {
        return Math.max(0, count - completedCount);
    }

    /** Completion fraction in [0.0, 1.0]; 0 when {@link #count} is zero. */
    public double completionFraction() {
        if (count <= 0) return 0.0;
        return (double) completedCount / count;
    }
}
