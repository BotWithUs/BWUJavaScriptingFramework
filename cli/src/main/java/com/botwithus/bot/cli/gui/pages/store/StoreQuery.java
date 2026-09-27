package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.ScriptCategory;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What the Store list is asked to show: a view, the filters and an order.
 * Immutable; each {@code with…} returns a changed copy.
 *
 * @param categories the categories to show; empty shows every category
 * @param search     matched against name, author and summary, ignoring case
 */
public record StoreQuery(
        StoreTab tab,
        PriceFilter price,
        Set<ScriptCategory> categories,
        boolean runsHereOnly,
        boolean madeByYouOnly,
        String search,
        StoreSort sort) {

    /** Everything, favourites first: what the Store opens on. */
    public static final StoreQuery DEFAULT = new StoreQuery(StoreTab.ALL, PriceFilter.ANY, Set.of(), false, false,
            "", StoreSort.FAVOURITES_FIRST);

    public StoreQuery {
        categories = Set.copyOf(categories);
        search = search == null ? "" : search;
    }

    /**
     * Whether a filter narrows the list. The view and the order are not filters:
     * they choose what to look at, not what to hide within it.
     */
    public boolean hasActiveFilters() {
        return price != PriceFilter.ANY || !categories.isEmpty() || runsHereOnly || madeByYouOnly
                || !search.isBlank();
    }

    /** Every filter off and back on the whole catalogue; the order is kept. */
    public StoreQuery cleared() {
        return new StoreQuery(StoreTab.ALL, PriceFilter.ANY, Set.of(), false, false, "", sort);
    }

    /** Whether {@code row} passes the view and every filter. */
    public boolean admits(StoreRow row) {
        return tab.admits(row.state())
                && price.admits(row.pricing())
                && (categories.isEmpty() || categories.contains(row.category()))
                && (!runsHereOnly || row.runsHere())
                && (!madeByYouOnly || row.isOwned())
                && row.matches(search.trim().toLowerCase(Locale.ROOT));
    }

    public StoreQuery withTab(StoreTab next) {
        return new StoreQuery(next, price, categories, runsHereOnly, madeByYouOnly, search, sort);
    }

    public StoreQuery withPrice(PriceFilter next) {
        return new StoreQuery(tab, next, categories, runsHereOnly, madeByYouOnly, search, sort);
    }

    /** Adds {@code category} to the filter, or removes it if it is already there. */
    public StoreQuery withCategoryToggled(ScriptCategory category) {
        Set<ScriptCategory> next = categories.isEmpty()
                ? EnumSet.noneOf(ScriptCategory.class) : EnumSet.copyOf(categories);
        if (!next.remove(category)) {
            next.add(category);
        }
        return new StoreQuery(tab, price, next, runsHereOnly, madeByYouOnly, search, sort);
    }

    public StoreQuery withRunsHereOnly(boolean next) {
        return new StoreQuery(tab, price, categories, next, madeByYouOnly, search, sort);
    }

    public StoreQuery withMadeByYouOnly(boolean next) {
        return new StoreQuery(tab, price, categories, runsHereOnly, next, search, sort);
    }

    public StoreQuery withSearch(String next) {
        return new StoreQuery(tab, price, categories, runsHereOnly, madeByYouOnly, next, sort);
    }

    public StoreQuery withSort(StoreSort next) {
        return new StoreQuery(tab, price, categories, runsHereOnly, madeByYouOnly, search, next);
    }
}
