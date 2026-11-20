package com.botwithus.bot.api.buff;

import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;

import java.util.Optional;

/**
 * An effect whose state the game keeps in a varbit, addressed by the varbit's gameval name
 * (the id it had when this was written is the fallback).
 *
 * <p>For a timed buff the varbit counts down in {@link Buffs#TIME_UNIT} steps, the unit xapi
 * read these varbits in. Buffs xapi listed whose varbit name says it is something else are
 * left out rather than guessed: "Powder of Protection" (its varbit is
 * {@code DUEL_ARENA_REMOVAL_SCARAB_POWDER_GREEN_DURATION}) and "Weapon poison" (its varbit is
 * {@code COMBATV2_P_POISON}, the player's own poison state).</p>
 */
public enum Buff {

    OVERLOAD("OVERLOAD_POTION_EFFECTS_LONG", 48834, true),
    ANTIFIRE("DRAGONRESIST_ORIGINAL", 497, true),
    SUPER_ANTIFIRE("DRAGONRESIST_ALL", 498, true),
    IRIT_INCENSE("INCENSE_IRIT_ACTIVE", 43714, true, "INCENSE_IRIT_POTENCY", 43696),
    KWUARM_INCENSE("INCENSE_KWUARM_ACTIVE", 43725, true, "INCENSE_KWUARM_POTENCY", 43707),
    LANTADYME_INCENSE("INCENSE_LANTADYME_ACTIVE", 43717, true, "INCENSE_LANTADYME_POTENCY", 43699),
    /** Active only: the varbit is a flag, not a timer. */
    AGGRESSION("WILDERNESS_AGGRESSION_POTION_EFFECTS", 33448, false),
    /** Active only: read from the combat side panel's flag. */
    PRAYER_RENEWAL("COMBATV2_TARGET_SIDE_PANEL_PLAYER_PRAYER_RENEW", 2100, false),
    /** Active only: read from the combat side panel's flag. */
    SUPER_PRAYER_RENEWAL("COMBATV2_TARGET_SIDE_PANEL_PLAYER_SUPER_PRAYERRENEW", 25852, false);

    private final GamevalRef varbit;
    private final boolean isTimed;
    private final GamevalRef potency;

    Buff(String varbitName, int varbitId, boolean isTimed) {
        this.varbit = new GamevalRef(GamevalType.VARBIT, varbitName, varbitId);
        this.isTimed = isTimed;
        this.potency = null;
    }

    Buff(String varbitName, int varbitId, boolean isTimed, String potencyName, int potencyId) {
        this.varbit = new GamevalRef(GamevalType.VARBIT, varbitName, varbitId);
        this.isTimed = isTimed;
        this.potency = new GamevalRef(GamevalType.VARBIT, potencyName, potencyId);
    }

    /** The varbit the buff's state is kept in. */
    public GamevalRef varbit() {
        return varbit;
    }

    /** True when {@link #varbit()} counts down the time left; false when it is only a flag. */
    public boolean isTimed() {
        return isTimed;
    }

    /** The varbit holding the buff's strength, for incense; empty for the others. */
    public Optional<GamevalRef> potencyVarbit() {
        return Optional.ofNullable(potency);
    }
}
