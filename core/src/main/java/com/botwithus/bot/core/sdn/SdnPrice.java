package com.botwithus.bot.core.sdn;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The price the Store headlines for a script: one billing tier, as the site
 * describes it.
 *
 * <p>A script can have several tiers. This is the one the site puts first, which
 * is not necessarily the cheapest, and a script with this price may still offer a
 * free tier as well — whether it does is {@link SdnCatalogueEntry#isFree()}, never
 * an inference from this.
 *
 * @param amount   the price in the site's currency, exact; never a {@code double},
 *                 which would round a price like 4.99
 * @param duration how long one payment lasts, in the site's own words, such as
 *                 {@code "monthly"}, {@code "yearly"} or {@code "one_time"}. Kept as
 *                 the site sent it, because only the display layer should decide
 *                 how to word a period the host has not seen before.
 */
public record SdnPrice(BigDecimal amount, String duration) {

    public SdnPrice {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(duration, "duration");
    }
}
