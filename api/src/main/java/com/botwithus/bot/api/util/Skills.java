package com.botwithus.bot.api.util;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.PlayerStat;

/**
 * Skill ID constants and stat lookup helpers for RS3.
 */
public final class Skills {

    private Skills() {}

    // Skill IDs (RS3 ordering)
    public static final int ATTACK = 0;
    public static final int DEFENCE = 1;
    public static final int STRENGTH = 2;
    public static final int CONSTITUTION = 3;
    public static final int RANGED = 4;
    public static final int PRAYER = 5;
    public static final int MAGIC = 6;
    public static final int COOKING = 7;
    public static final int WOODCUTTING = 8;
    public static final int FLETCHING = 9;
    public static final int FISHING = 10;
    public static final int FIREMAKING = 11;
    public static final int CRAFTING = 12;
    public static final int SMITHING = 13;
    public static final int MINING = 14;
    public static final int HERBLORE = 15;
    public static final int AGILITY = 16;
    public static final int THIEVING = 17;
    public static final int SLAYER = 18;
    public static final int FARMING = 19;
    public static final int RUNECRAFTING = 20;
    public static final int HUNTER = 21;
    public static final int CONSTRUCTION = 22;
    public static final int SUMMONING = 23;
    public static final int DUNGEONEERING = 24;
    public static final int DIVINATION = 25;
    public static final int INVENTION = 26;
    public static final int ARCHAEOLOGY = 27;
    public static final int NECROMANCY = 28;

    /**
     * Returns the base (real) level of a skill, not including boosts or drains.
     *
     * @param api     the game API instance
     * @param skillId the skill ID constant (e.g., {@link #ATTACK}, {@link #MINING})
     * @return the base level, or 0 if unavailable
     */
    public static int getLevel(GameAPI api, int skillId) {
        PlayerStat stat = api.getPlayerStat(skillId);
        return stat != null ? stat.level() : 0;
    }

    /**
     * Returns the current level of a skill, including boosts and drains.
     *
     * @param api     the game API instance
     * @param skillId the skill ID constant
     * @return the boosted level, or 0 if unavailable
     */
    public static int getBoostedLevel(GameAPI api, int skillId) {
        PlayerStat stat = api.getPlayerStat(skillId);
        return stat != null ? stat.boostedLevel() : 0;
    }

    /**
     * Returns the total experience points in a skill.
     *
     * @param api     the game API instance
     * @param skillId the skill ID constant
     * @return the XP value, or 0 if unavailable
     */
    public static int getXp(GameAPI api, int skillId) {
        PlayerStat stat = api.getPlayerStat(skillId);
        return stat != null ? stat.xp() : 0;
    }
}
