package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

import java.util.List;

/**
 * The numbers the Store shows beside its views and price filter. Counted over
 * the whole catalogue, never the filtered list, so a count does not change as
 * filters are switched.
 *
 * @param free scripts the catalogue says are free; unknown pricing counts in neither
 * @param paid scripts the catalogue says are not free
 */
public record StoreCounts(int all, int installed, int updates, int notInstalled, int free, int paid) {

    public static final StoreCounts NONE = new StoreCounts(0, 0, 0, 0, 0, 0);

    public static StoreCounts of(List<StoreRow> rows) {
        int installed = 0;
        int updates = 0;
        int free = 0;
        int paid = 0;
        for (StoreRow r : rows) {
            installed += r.state().isOnThisPc() ? 1 : 0;
            updates += r.state().hasUpdate() ? 1 : 0;
            free += r.pricing() == Pricing.FREE ? 1 : 0;
            paid += r.pricing() == Pricing.PAID ? 1 : 0;
        }
        return new StoreCounts(rows.size(), installed, updates, rows.size() - installed, free, paid);
    }

    /** The count beside {@code tab}'s segment. */
    public int of(StoreTab tab) {
        return switch (tab) {
            case ALL -> all;
            case INSTALLED -> installed;
            case UPDATES -> updates;
            case NOT_INSTALLED -> notInstalled;
        };
    }
}
