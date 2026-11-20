package com.botwithus.bot.api.hud;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's own HUD controls. Obtained from {@link GameAPI#hud()}.
 *
 * <p>Side windows are opened from the ribbon ({@code TOPLEVEL_V2_RIBBON}) by the option the
 * button lists, found over the ribbon's component tree and its live ops, as a player's click
 * on the ribbon would. Leaving the world goes through the Escape menu, by the agent's
 * {@code logout} and {@code exit_to_lobby}: the game's own path, the same clicks a player
 * makes. Neither touches the launcher's saved session or any credential.</p>
 */
public final class Hud {

    private static final Logger log = LoggerFactory.getLogger(Hud.class);

    private static final GamevalRef RIBBON = new GamevalRef(GamevalType.INTERFACE,
            "TOPLEVEL_V2_RIBBON", 1431);

    private final GameAPI api;

    public Hud(GameAPI api) {
        this.api = api;
    }

    /** True when {@code tab}'s window is open. Snapshot read: no round-trip. */
    public boolean isOpen(HudTab tab) {
        return api.isInterfaceOpen(tab.window().resolve(api.gamevals()));
    }

    /**
     * Opens {@code tab}'s window from the ribbon. Does nothing, and returns {@code true}, when
     * it is already open: the ribbon button toggles, so pressing it again would close it.
     *
     * @return {@code true} when the window is open or the press was queued; {@code false}
     *         when the ribbon lists no button with the tab's option
     */
    public boolean open(HudTab tab) {
        if (isOpen(tab)) {
            return true;
        }
        ComponentNode button = api.components().in(RIBBON.resolve(api.gamevals()))
                .withOption(tab.option()).first();
        if (button == null) {
            log.debug("the ribbon lists no '{}'", tab.option());
            return false;
        }
        return button.interact(tab.option());
    }

    /**
     * Logs out to the login screen through the Escape menu's Logout and its confirmation.
     * Poll the login state for the login screen; it is not reached by the time this returns.
     *
     * @return {@code true} when the clicks were queued; {@code false} when the agent refused
     *         (not in the world, actions blocked, the menu not found)
     * @see GameAPI#logout()
     */
    public boolean logout() {
        return api.logout();
    }

    /**
     * Leaves the world for the lobby through the Escape menu's Exit to Lobby.
     *
     * @return {@code true} when the click was queued; {@code false} when the agent refused
     * @see GameAPI#exitToLobby()
     */
    public boolean exitToLobby() {
        return api.exitToLobby();
    }
}
