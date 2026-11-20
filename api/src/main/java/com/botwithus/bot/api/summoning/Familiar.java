package com.botwithus.bot.api.summoning;

import com.botwithus.bot.api.model.ItemType;

import java.util.Optional;

/**
 * A familiar, as the cache defines it through its summoning pouch: the pouch item carries
 * the familiar's NPC and its requirements as item params.
 *
 * @param pouchItemId    the pouch item id (what {@code FOLLOWER_OBJ} holds while summoned)
 * @param pouchName      the pouch's item name ({@code "Pack yak pouch"})
 * @param npcId          the familiar's NPC type ({@code FOLLOWER_NPC})
 * @param levelRequired  Summoning level needed ({@code LORE_REQUIREMENT})
 * @param summoningCost  summoning points it costs to summon ({@code LORE_COST})
 */
public record Familiar(int pouchItemId, String pouchName, int npcId, int levelRequired,
                       int summoningCost) {

    /** {@code FOLLOWER_NPC}. */
    public static final int PARAM_FOLLOWER_NPC = 74;
    /** {@code LORE_REQUIREMENT}. */
    public static final int PARAM_LEVEL_REQUIRED = 394;
    /** {@code LORE_COST}. */
    public static final int PARAM_COST = 396;
    private static final int UNKNOWN = -1;

    /** The familiar {@code pouch} summons, or empty when the item is not a summoning pouch. */
    public static Optional<Familiar> fromPouch(ItemType pouch) {
        if (pouch == null) {
            return Optional.empty();
        }
        var npc = pouch.intParam(PARAM_FOLLOWER_NPC);
        if (npc.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Familiar(pouch.id(), pouch.name(), npc.getAsInt(),
                pouch.intParam(PARAM_LEVEL_REQUIRED).orElse(UNKNOWN),
                pouch.intParam(PARAM_COST).orElse(UNKNOWN)));
    }
}
