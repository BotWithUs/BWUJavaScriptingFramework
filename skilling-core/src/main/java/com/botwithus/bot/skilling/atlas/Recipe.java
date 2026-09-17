package com.botwithus.bot.skilling.atlas;

import java.util.List;

/**
 * A production recipe reconstructed by the analyzer from item {@code RECIPE_*}
 * params, read out of the Atlas {@code recipe.json} blob. This is the data the
 * dependency planner expands: to make {@code product}, you need {@code ingredients}
 * (each an item id + count), at the given skill {@code requirements}, yielding
 * {@code xp}, using {@code tools}.
 *
 * @param product      output item id
 * @param productName  gameval symbolic name (e.g. {@code "BRONZE_BAR"}), or {@code null}
 * @param ingredients  inputs consumed
 * @param requirements skill-level gates
 * @param xp           xp awarded
 * @param tools        tools required (not consumed), e.g. a hammer
 * @param makeQuantity units produced per craft
 * @param members      members-only
 * @param skill        primary production skill name, or {@code null}
 */
public record Recipe(int product, String productName,
                     List<Ingredient> ingredients,
                     List<Requirement> requirements,
                     List<XpReward> xp,
                     List<Tool> tools,
                     int makeQuantity, boolean members, String skill) {

    /** One input: {@code count} of item {@code item}. */
    public record Ingredient(int item, String name, int count) {}

    /** A skill-level gate: {@code level} in {@code skill} (id {@code skillId}). */
    public record Requirement(int skillId, String skill, int level) {}

    /** Xp awarded in {@code skill} (id {@code skillId}). */
    public record XpReward(int skillId, String skill, double xp) {}

    /** A required (non-consumed) tool item. */
    public record Tool(int item, String name) {}
}
