package com.botwithus.bot.api.summoning;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.api.util.SkillType;

import java.util.Optional;

/**
 * Summoning points and the summoned familiar: what it is, how long it has left, its special
 * move, and the combat-bar familiar buttons.
 *
 * <p>Reads are varps/varbits addressed by gameval name; the familiar's static data comes
 * from the cache through its pouch ({@link Familiar}). The buttons press the familiar
 * options on {@code TOPLEVEL_V2_COMBAT_BAR}; each returns whether the press was queued.</p>
 */
public final class Summoning {

    private static final int POINTS_PER_LEVEL = 10;
    private static final int NO_POUCH = 0;
    private static final int OP_PRESS = 1;
    private static final int NO_SUB_INDEX = -1;
    private static final int COMBAT_BAR = 1430;
    private static final int FAMILIAR_SIDE = 662;

    private static final GamevalRef POINTS = new GamevalRef(GamevalType.VARBIT, "LORE_POINTS", 41524);
    private static final GamevalRef POUCH = new GamevalRef(GamevalType.VARP, "FOLLOWER_OBJ", 1831);
    private static final GamevalRef MINUTES_LEFT =
            new GamevalRef(GamevalType.VARBIT, "LORE_FULL_TIMER", 6055);
    private static final GamevalRef SPECIAL_POINTS =
            new GamevalRef(GamevalType.VARP, "LORE_SPELL_POINTS", 1787);
    private static final GamevalRef SCROLLS_STORED =
            new GamevalRef(GamevalType.VARBIT, "COMBATV2_SUMMONING_SCROLLCOUNT", 25412);

    private static final GamevalRef CALL_BUTTON = button("TOPLEVEL_V2_COMBAT_BAR__CALL_OP1", 50);
    private static final GamevalRef DISMISS_BUTTON =
            button("TOPLEVEL_V2_COMBAT_BAR__DISMISS_OP1", 51);
    private static final GamevalRef INTERACT_BUTTON =
            button("TOPLEVEL_V2_COMBAT_BAR__INTERACT_OP1", 44);
    private static final GamevalRef SPECIAL_BUTTON = new GamevalRef(GamevalType.COMPONENT,
            "LORE_STATS_SIDE__LORE_SPELL_BUTTON_GRAPHIC_1", Interfaces.componentHash(FAMILIAR_SIDE, 38));

    private final GameAPI api;
    private final BeastOfBurden beastOfBurden;

    public Summoning(GameAPI api) {
        this.api = api;
        this.beastOfBurden = new BeastOfBurden(api);
    }

    /** Current summoning points as the game stores them ({@code LORE_POINTS}). */
    public int points() {
        return api.getVarbit(POINTS.resolve(api.gamevals()));
    }

    /** Base Summoning level times ten; {@code -1} when the level cannot be read. */
    public int maxPoints() {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null || snapshot.self() == null) {
            return -1;
        }
        return snapshot.self().skills().stream()
                .filter(skill -> skill.typeId() == SkillType.SUMMONING.id())
                .mapToInt(skill -> skill.actualLevel() * POINTS_PER_LEVEL)
                .findFirst().orElse(-1);
    }

    /** The pouch item of the summoned familiar ({@code FOLLOWER_OBJ}), or {@code 0} for none. */
    public int pouchId() {
        int pouch = api.getVarp(POUCH.resolve(api.gamevals()));
        return Math.max(pouch, NO_POUCH);
    }

    /** True when a familiar is summoned. */
    public boolean hasFamiliar() {
        return pouchId() > NO_POUCH;
    }

    /** The summoned familiar's cache data; empty with no familiar or no cache. */
    public Optional<Familiar> familiar() {
        int pouch = pouchId();
        if (pouch == NO_POUCH) {
            return Optional.empty();
        }
        try {
            return Familiar.fromPouch(api.getItemType(pouch));
        } catch (IllegalStateException noCache) {
            return Optional.empty();
        }
    }

    /** Minutes the familiar has left ({@code LORE_FULL_TIMER}); {@code 0} with none. */
    public int minutesRemaining() {
        return Math.max(0, api.getVarbit(MINUTES_LEFT.resolve(api.gamevals())));
    }

    /** Special-move points ({@code LORE_SPELL_POINTS}). */
    public int specialMovePoints() {
        return api.getVarp(SPECIAL_POINTS.resolve(api.gamevals()));
    }

    /** Scrolls stored on the familiar ({@code COMBATV2_SUMMONING_SCROLLCOUNT}). */
    public int scrollsStored() {
        return Math.max(0, api.getVarbit(SCROLLS_STORED.resolve(api.gamevals())));
    }

    /** The beast-of-burden inventory. */
    public BeastOfBurden beastOfBurden() {
        return beastOfBurden;
    }

    /** Presses "Call familiar". False when no familiar is summoned. */
    public boolean call() {
        return press(CALL_BUTTON);
    }

    /** Presses "Dismiss familiar". False when no familiar is summoned. */
    public boolean dismiss() {
        return press(DISMISS_BUTTON);
    }

    /** Presses the familiar's "Interact" option. False when no familiar is summoned. */
    public boolean interact() {
        return press(INTERACT_BUTTON);
    }

    /** Casts the familiar's special move. False when no familiar is summoned. */
    public boolean castSpecial() {
        return press(SPECIAL_BUTTON);
    }

    private boolean press(GamevalRef button) {
        if (!hasFamiliar()) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_PRESS, NO_SUB_INDEX,
                button.resolve(api.gamevals())));
        return true;
    }

    private static GamevalRef button(String gameval, int componentId) {
        return new GamevalRef(GamevalType.COMPONENT, gameval,
                Interfaces.componentHash(COMBAT_BAR, componentId));
    }
}
