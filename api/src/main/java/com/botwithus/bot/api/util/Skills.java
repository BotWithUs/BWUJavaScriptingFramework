package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PlayerStat;

/**
 * Skill ID constants and stat lookup helpers for RS3.
 *
 * <p>Thin convenience wrapper over {@link GameAPI#getPlayerStat(int)} —
 * scripts that already hold a {@link PlayerStat} can read fields directly;
 * this class is for the common case of "what's my Necromancy level right
 * now" without threading a stat object around.</p>
 *
 * <pre>{@code
 * if (Skills.getBoostedLevel(api, Skills.NECROMANCY) >= 95) {
 *     // ...
 * }
 * }</pre>
 */
public final class Skills {

    private Skills() {}

    // Skill type IDs (RS3 ordering — matches the in-game StatType.id).
    public static final int ATTACK        = 0;
    public static final int DEFENCE       = 1;
    public static final int STRENGTH      = 2;
    public static final int CONSTITUTION  = 3;
    public static final int RANGED        = 4;
    public static final int PRAYER        = 5;
    public static final int MAGIC         = 6;
    public static final int COOKING       = 7;
    public static final int WOODCUTTING   = 8;
    public static final int FLETCHING     = 9;
    public static final int FISHING       = 10;
    public static final int FIREMAKING    = 11;
    public static final int CRAFTING      = 12;
    public static final int SMITHING      = 13;
    public static final int MINING        = 14;
    public static final int HERBLORE      = 15;
    public static final int AGILITY       = 16;
    public static final int THIEVING      = 17;
    public static final int SLAYER        = 18;
    public static final int FARMING       = 19;
    public static final int RUNECRAFTING  = 20;
    public static final int HUNTER        = 21;
    public static final int CONSTRUCTION  = 22;
    public static final int SUMMONING     = 23;
    public static final int DUNGEONEERING = 24;
    public static final int DIVINATION    = 25;
    public static final int INVENTION     = 26;
    public static final int ARCHAEOLOGY   = 27;
    public static final int NECROMANCY    = 28;

    /**
     * Base (unboosted) level for {@code skillId}. Returns {@code 0} when not
     * in-game or when the stat hasn't been published yet.
     */
    public static int getLevel(GameAPI api, int skillId) {
        PlayerStat stat = api.getPlayerStat(skillId);
        return stat != null ? stat.level() : 0;
    }

    /**
     * Current displayed level for {@code skillId} including stat boosts and
     * drains. Returns {@code 0} when not in-game.
     */
    public static int getBoostedLevel(GameAPI api, int skillId) {
        PlayerStat stat = api.getPlayerStat(skillId);
        return stat != null ? stat.boostedLevel() : 0;
    }

    /**
     * Total xp for {@code skillId}. Returns {@code 0} when not in-game.
     */
    public static int getXp(GameAPI api, int skillId) {
        PlayerStat stat = api.getPlayerStat(skillId);
        return stat != null ? stat.experience() : 0;
    }
}
