package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

import java.util.Locale;
import java.util.Objects;

/**
 * One catalogue script as the Store lists it: what the catalogue says about it,
 * plus where it stands on this PC.
 *
 * @param summary      one display line: the tagline, else the description's first line
 * @param priceText    the headline price in words, such as {@code "4.99 per month"}; empty
 *                     when the catalogue reported none
 * @param storeVersion the catalogue's own version label; free text, for display only
 * @param runsHere     the script supports this host's agent
 * @param runsOnOlder  the script supports the older agent
 * @param isOwned      the signed-in account wrote it
 * @param isInstalling an install of it is in flight
 */
public record StoreRow(
        String id,
        String name,
        String author,
        String summary,
        String description,
        ScriptCategory category,
        Pricing pricing,
        String priceText,
        String storeVersion,
        String scriptClass,
        boolean runsHere,
        boolean runsOnOlder,
        boolean isOwned,
        RowState state,
        boolean isInstalling,
        boolean isFavourite) {

    public StoreRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
    }

    /**
     * Whether ticking it, or its row action, can install it: it runs here, it is not
     * already on its way, and there is something to install (a first copy or a newer build).
     */
    public boolean isInstallable() {
        return runsHere && !isInstalling && (!state.isOnThisPc() || state.hasUpdate());
    }

    /** Whether {@code lowerCaseQuery} appears in its name, author or summary. */
    public boolean matches(String lowerCaseQuery) {
        return lowerCaseQuery.isEmpty()
                || name.toLowerCase(Locale.ROOT).contains(lowerCaseQuery)
                || author.toLowerCase(Locale.ROOT).contains(lowerCaseQuery)
                || summary.toLowerCase(Locale.ROOT).contains(lowerCaseQuery);
    }

    /** The same row with its favourite flag set to {@code favourite}. */
    public StoreRow withFavourite(boolean favourite) {
        return new StoreRow(id, name, author, summary, description, category, pricing, priceText, storeVersion,
                scriptClass, runsHere, runsOnOlder, isOwned, state, isInstalling, favourite);
    }

    /** The same row with its in-flight flag set to {@code installing}. */
    public StoreRow withInstalling(boolean installing) {
        return new StoreRow(id, name, author, summary, description, category, pricing, priceText, storeVersion,
                scriptClass, runsHere, runsOnOlder, isOwned, state, installing, isFavourite);
    }
}
