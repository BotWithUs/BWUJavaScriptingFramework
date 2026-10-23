package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

/**
 * The words and the action a Store row shows, decided in one place so the list,
 * the favourite tiles and the detail pane never disagree.
 */
final class RowPresentation {

    /** What a row's button does. */
    enum Action { OPEN, UPDATE, INSTALL, CANNOT_INSTALL, INSTALLING }

    /** How a state label is coloured. */
    enum Tone { OK, UPDATE, MUTED, BUSY }

    record StateLabel(String text, Tone tone) {}

    private RowPresentation() {}

    /**
     * An install in flight wins, then a script built only for the older agent, then
     * where it stands on this PC.
     */
    static StateLabel state(StoreRow row) {
        if (row.isInstalling()) {
            return new StateLabel("Installing…", Tone.BUSY);
        }
        if (!row.runsHere()) {
            return new StateLabel("Older agent only", Tone.MUTED);
        }
        return switch (row.state()) {
            case RowState.UpdateAvailable u -> new StateLabel(builds(u), Tone.UPDATE);
            case RowState.Installed ignored -> new StateLabel("Installed", Tone.OK);
            case RowState.NotInstalled ignored -> new StateLabel("Not installed", Tone.MUTED);
        };
    }

    static Action action(StoreRow row) {
        if (row.isInstalling()) {
            return Action.INSTALLING;
        }
        if (!row.runsHere()) {
            return Action.CANNOT_INSTALL;
        }
        return switch (row.state()) {
            case RowState.UpdateAvailable ignored -> Action.UPDATE;
            case RowState.Installed ignored -> Action.OPEN;
            case RowState.NotInstalled ignored -> Action.INSTALL;
        };
    }

    /** The row's price badge; empty when the catalogue did not say, so no badge is drawn. */
    static String price(Pricing pricing) {
        return switch (pricing) {
            case FREE -> "Free";
            case PAID -> "Paid";
            case UNKNOWN -> "";
        };
    }

    /** A favourite tile's second line, such as {@code "Update to build 7 · Paid"}. */
    static String tileLine(StoreRow row) {
        String state = switch (action(row)) {
            case INSTALLING -> "Installing…";
            case CANNOT_INSTALL -> "Older agent only";
            case UPDATE -> "Update to build " + to(row.state());
            case OPEN -> "Installed";
            case INSTALL -> "Not installed";
        };
        String price = price(row.pricing());
        return price.isEmpty() ? state : state + " · " + price;
    }

    /** The detail pane's "On this PC" value. */
    static String onThisPc(StoreRow row) {
        return switch (row.state()) {
            case RowState.UpdateAvailable u -> "build " + u.from() + " · update ready";
            case RowState.Installed ignored -> "installed";
            case RowState.NotInstalled n -> n.wasInstalledBefore() ? "not loaded" : "not installed";
        };
    }

    /** The detail pane's price value. */
    static String access(StoreRow row) {
        String price = row.priceText();
        return switch (row.pricing()) {
            case FREE -> price.isEmpty() ? "free" : "free · or " + price;
            case PAID -> price.isEmpty() ? "paid" : price;
            case UNKNOWN -> price.isEmpty() ? "not reported" : price;
        };
    }

    private static String builds(RowState.UpdateAvailable u) {
        return "build " + u.from() + " → " + u.to();
    }

    private static int to(RowState state) {
        return switch (state) {
            case RowState.UpdateAvailable u -> u.to();
            case RowState.Installed ignored -> 0;
            case RowState.NotInstalled ignored -> 0;
        };
    }
}
