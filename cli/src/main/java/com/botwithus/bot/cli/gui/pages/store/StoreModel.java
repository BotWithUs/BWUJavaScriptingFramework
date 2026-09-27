package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.nav.SecondLine;

import java.util.List;

/**
 * What the Script Store page reads and asks for. The live model reads the host's
 * catalogue, installs and favourites; the dev preview supplies fixtures. Every
 * method runs on the render thread and returns promptly: anything slow happens
 * elsewhere and shows up in a later {@link #view}.
 */
public interface StoreModel {

    /** This frame's catalogue, filtered and ordered by {@code query}. */
    StoreView view(StoreQuery query);

    /** Asks the launcher for the catalogue now (Refresh, Try again). */
    void refresh();

    /** Stars {@code id} if it is not a favourite, else unstars it. */
    void toggleFavourite(String id);

    /**
     * Installs the scripts {@code ids} name, as one batch. Ids that are not in the
     * catalogue or cannot be installed right now are left out; nothing happens when
     * none is left.
     */
    void install(List<String> ids);

    /** Clears the line about the last install. */
    void dismissInstallMessage();

    /** Records that the user has now seen every script in the catalogue shown. */
    void markSeen();

    /** The Store's line in the sidebar: the sign-in state, and how many scripts are new. */
    SecondLine.AccountStatus signInLine();
}
