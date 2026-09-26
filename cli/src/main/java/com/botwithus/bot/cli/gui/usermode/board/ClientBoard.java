package com.botwithus.bot.cli.gui.usermode.board;

import java.util.List;
import java.util.Optional;

/**
 * The data behind Normal mode. The live implementation reads the connected game
 * clients; the dev-only preview supplies fixtures through the same seam, so both
 * render through exactly the same UI code.
 */
public interface ClientBoard {

    /** The clients to show, in a stable order. Called every frame. */
    List<ClientView> clients();

    BoardStatus status();

    /** The installed scripts. Called once when the picker opens, not per frame. */
    List<ScriptEntry> catalog();

    /**
     * The picker's "Your subscriptions" group as seen from {@code clientId}: which
     * subscribed scripts exist, and whether each is installed there. Called every
     * frame while the picker is open, so install progress shows as it happens.
     */
    SubscriptionGroup subscriptions(String clientId);

    /** The running script on {@code clientId}, if any, for the config inspector. */
    Optional<InspectorTarget> inspect(String clientId);

    ClientActions actions();
}
