package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

/**
 * The Store's price filter. A script whose pricing the catalogue did not report
 * matches neither Free nor Paid: guessing either would put it under the wrong one.
 */
public enum PriceFilter {

    ANY("Any"),
    FREE("Free"),
    PAID("Paid");

    private final String label;

    PriceFilter(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean admits(Pricing pricing) {
        return switch (this) {
            case ANY -> true;
            case FREE -> pricing == Pricing.FREE;
            case PAID -> pricing == Pricing.PAID;
        };
    }
}
