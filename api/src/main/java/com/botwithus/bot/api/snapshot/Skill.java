package com.botwithus.bot.api.snapshot;

/**
 * One row of the local player's skills array.
 *
 * @param typeId        skill type id ({@code StatType.id} from the game cache)
 * @param experience    total xp; fits in {@code i32} (in-game cap is 200,000,000)
 * @param actualLevel   base/true level (e.g. 99)
 * @param boostedLevel  current displayed level including buffs/drains
 */
public record Skill(
        int typeId,
        int experience,
        int actualLevel,
        int boostedLevel
) {}
