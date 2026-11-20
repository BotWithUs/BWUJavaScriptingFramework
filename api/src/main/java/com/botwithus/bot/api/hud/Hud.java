package com.botwithus.bot.api.hud;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.OptionalInt;

/**
 * The game's own HUD controls. Obtained from {@link GameAPI#hud()}.
 *
 * <p>Each ribbon button is a sub of {@code TOPLEVEL_V2_RIBBON}'s button layer, one per slot;
 * the cache enum {@code RIBBON_WINDOWS} says which window each slot opens. A button lists
 * {@value #OPEN} while its window is not showing and {@value #CLOSE} while it is (seen live on
 * 951-4), so the button's own menu is both the state and the control. A docked window stays
 * loaded when another tab is in front of it, so whether its interface is open says nothing.</p>
 *
 * <p>Leaving the world goes through the Escape menu, by the agent's {@code logout} and
 * {@code exit_to_lobby}: the game's own path, the clicks a player makes. Neither touches the
 * launcher's saved session or any credential.</p>
 */
public final class Hud {

    private static final Logger log = LoggerFactory.getLogger(Hud.class);

    private static final int RIBBON_ID = 1431;
    /** The layer whose subs are the ribbon buttons, one per slot. */
    private static final GamevalRef RIBBON_BUTTONS = new GamevalRef(GamevalType.COMPONENT,
            "TOPLEVEL_V2_RIBBON__WINDOW_BUILD", Interfaces.componentHash(RIBBON_ID, 0));
    private static final GamevalRef RIBBON_WINDOWS = new GamevalRef(GamevalType.ENUM,
            "RIBBON_WINDOWS", 13319);

    static final String OPEN = "Open";
    static final String CLOSE = "Close";

    private final GameAPI api;

    public Hud(GameAPI api) {
        this.api = api;
    }

    /** True when {@code tab}'s window is showing: its ribbon button offers Close. One read. */
    public boolean isOpen(HudTab tab) {
        return buttonOps(tab).opNamed(CLOSE).isPresent();
    }

    /**
     * Brings {@code tab}'s window to the front by its ribbon button. Presses nothing, and
     * returns {@code true}, when it is already showing: the button would close it.
     *
     * @return {@code true} when the window is showing or the press was queued; {@code false}
     *         when the button offers neither option (the ribbon is not loaded)
     */
    public boolean open(HudTab tab) {
        ComponentOps ops = buttonOps(tab);
        if (ops.opNamed(CLOSE).isPresent()) {
            return true;
        }
        OptionalInt open = ops.opNamed(OPEN);
        if (open.isEmpty()) {
            log.debug("ribbon slot for {} lists no '{}'", tab, OPEN);
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, open.getAsInt(), slotOf(tab),
                RIBBON_BUTTONS.resolve(api.gamevals())));
        return true;
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

    /** The ribbon slot whose button opens {@code tab}, from {@code RIBBON_WINDOWS}. */
    int slotOf(HudTab tab) {
        EnumType windows = ribbonWindows();
        if (windows != null) {
            for (Map.Entry<Integer, Integer> entry : windows.intEntries().entrySet()) {
                if (entry.getValue() == tab.window()) {
                    return entry.getKey();
                }
            }
        }
        return tab.fallbackSlot();
    }

    private ComponentOps buttonOps(HudTab tab) {
        int buttons = RIBBON_BUTTONS.resolve(api.gamevals());
        ComponentOps ops = api.getComponentOps(Interfaces.interfaceIdFromHash(buttons),
                Interfaces.componentIdFromHash(buttons), slotOf(tab));
        return ops == null ? ComponentOps.NONE : ops;
    }

    private EnumType ribbonWindows() {
        try {
            return api.getEnumType(RIBBON_WINDOWS.resolve(api.gamevals()));
        } catch (RuntimeException e) {
            log.debug("RIBBON_WINDOWS unreadable; using the fallback slots", e);
            return null;
        }
    }
}
