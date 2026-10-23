package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.core.sdn.SdnPrice;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Words for a catalogue price. The site sends an exact amount and a billing period
 * in its own vocabulary; this is the one place that period becomes English. The
 * site's currency is not on the wire, so no currency symbol is invented.
 */
public final class StorePrices {

    /** Prices are shown to the cent at least, so 5 reads as 5.00 next to 4.99. */
    private static final int MIN_DECIMALS = 2;

    private StorePrices() {}

    /** Such as {@code "4.99 per month"}; empty for no price. */
    public static String describe(SdnPrice price) {
        if (price == null) {
            return "";
        }
        return amount(price.amount()) + " " + period(price.duration());
    }

    private static String amount(BigDecimal amount) {
        BigDecimal shown = amount.scale() < MIN_DECIMALS ? amount.setScale(MIN_DECIMALS) : amount;
        return shown.toPlainString();
    }

    /** The billing period in words. A period this host does not know is shown as the site wrote it. */
    static String period(String duration) {
        return switch (duration.toLowerCase(Locale.ROOT)) {
            case "one_time" -> "once";
            case "two_day" -> "per 2 days";
            case "weekly" -> "per week";
            case "fortnightly" -> "per 2 weeks";
            case "monthly" -> "per month";
            case "yearly" -> "per year";
            default -> duration.replace('_', ' ');
        };
    }
}
