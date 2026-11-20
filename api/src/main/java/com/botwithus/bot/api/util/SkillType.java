package com.botwithus.bot.api.util;

import java.util.Optional;

/**
 * The game's skills, keyed by the id the client uses for them ({@code StatType.id}, the same
 * id {@link com.botwithus.bot.api.GameAPI#getPlayerStat(int)} takes). The {@code int}
 * constants on {@link Skills} carry the same ids for code that switches on them.
 *
 * <pre>{@code
 * int lvl = Skills.getLevel(api, SkillType.WOODCUTTING);
 * int toGo = Skills.xpToNextLevel(api, SkillType.WOODCUTTING);
 * }</pre>
 */
public enum SkillType {
    ATTACK(Skills.ATTACK, "Attack"),
    DEFENCE(Skills.DEFENCE, "Defence"),
    STRENGTH(Skills.STRENGTH, "Strength"),
    CONSTITUTION(Skills.CONSTITUTION, "Constitution"),
    RANGED(Skills.RANGED, "Ranged"),
    PRAYER(Skills.PRAYER, "Prayer"),
    MAGIC(Skills.MAGIC, "Magic"),
    COOKING(Skills.COOKING, "Cooking"),
    WOODCUTTING(Skills.WOODCUTTING, "Woodcutting"),
    FLETCHING(Skills.FLETCHING, "Fletching"),
    FISHING(Skills.FISHING, "Fishing"),
    FIREMAKING(Skills.FIREMAKING, "Firemaking"),
    CRAFTING(Skills.CRAFTING, "Crafting"),
    SMITHING(Skills.SMITHING, "Smithing"),
    MINING(Skills.MINING, "Mining"),
    HERBLORE(Skills.HERBLORE, "Herblore"),
    AGILITY(Skills.AGILITY, "Agility"),
    THIEVING(Skills.THIEVING, "Thieving"),
    SLAYER(Skills.SLAYER, "Slayer"),
    FARMING(Skills.FARMING, "Farming"),
    RUNECRAFTING(Skills.RUNECRAFTING, "Runecrafting"),
    HUNTER(Skills.HUNTER, "Hunter"),
    CONSTRUCTION(Skills.CONSTRUCTION, "Construction"),
    SUMMONING(Skills.SUMMONING, "Summoning"),
    DUNGEONEERING(Skills.DUNGEONEERING, "Dungeoneering"),
    DIVINATION(Skills.DIVINATION, "Divination"),
    INVENTION(Skills.INVENTION, "Invention", true),
    ARCHAEOLOGY(Skills.ARCHAEOLOGY, "Archaeology"),
    NECROMANCY(Skills.NECROMANCY, "Necromancy");

    private final int id;
    private final String displayName;
    private final boolean isElite;

    SkillType(int id, String displayName) {
        this(id, displayName, false);
    }

    SkillType(int id, String displayName, boolean isElite) {
        this.id = id;
        this.displayName = displayName;
        this.isElite = isElite;
    }

    /** The client's id for this skill. */
    public int id() {
        return id;
    }

    /** The English name the skills panel shows. */
    public String displayName() {
        return displayName;
    }

    /** Whether this skill levels on the {@link XpTable#ELITE elite} curve. */
    public boolean isElite() {
        return isElite;
    }

    /** The xp curve this skill levels on. */
    public XpTable xpTable() {
        return isElite ? XpTable.ELITE : XpTable.STANDARD;
    }

    /** The skill with the given client id, or empty when no skill has it. */
    public static Optional<SkillType> byId(int id) {
        for (SkillType skill : values()) {
            if (skill.id == id) {
                return Optional.of(skill);
            }
        }
        return Optional.empty();
    }
}
