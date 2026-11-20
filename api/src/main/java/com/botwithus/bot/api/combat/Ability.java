package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.model.StructType;

import java.util.Optional;

/**
 * A combat ability (or prayer, or other action-bar entry) as the cache defines it: one
 * {@code COMBATV2_*} struct.
 *
 * @param structId            the ability's struct id
 * @param typeCode            the action-bar slot type it lives under (the key into the
 *                            cache enum {@code COMBATV2_TYPE_TO_ENUM})
 * @param abilityId           its id within that type ({@code COMBATV2_ABILITY_ID})
 * @param name                its display name ({@code COMBATV2_ABILITY_NAME})
 * @param cooldownTicks       its cooldown in server ticks ({@code COMBATV2_ABILITY_COOLDOWN});
 *                            {@code 0} when it has none
 * @param globalCooldownTicks the global cooldown it triggers, in server ticks
 */
public record Ability(int structId, int typeCode, int abilityId, String name, int cooldownTicks,
                      int globalCooldownTicks) {

    /** {@code COMBATV2_ABILITY_ID}. */
    public static final int PARAM_ABILITY_ID = 2793;
    /** {@code COMBATV2_ABILITY_NAME}. */
    public static final int PARAM_NAME = 2794;
    /** {@code COMBATV2_ABILITY_COOLDOWN}, in server ticks. */
    public static final int PARAM_COOLDOWN = 2796;
    /** {@code COMBATV2_GLOBAL_COOLDOWN}, in server ticks. */
    public static final int PARAM_GLOBAL_COOLDOWN = 9405;
    /** The cache's default for {@link #PARAM_GLOBAL_COOLDOWN} when a struct leaves it out. */
    public static final int DEFAULT_GLOBAL_COOLDOWN_TICKS = 3;

    public Ability {
        if (name == null) {
            throw new IllegalArgumentException("name");
        }
    }

    /**
     * Builds the ability {@code struct} defines, or empty when it carries no ability id or
     * name (it is then not an ability struct).
     */
    public static Optional<Ability> fromStruct(int typeCode, StructType struct) {
        if (struct == null) {
            return Optional.empty();
        }
        var abilityId = struct.intParam(PARAM_ABILITY_ID);
        Optional<String> name = struct.stringParam(PARAM_NAME);
        if (abilityId.isEmpty() || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Ability(struct.id(), typeCode, abilityId.getAsInt(), name.get(),
                struct.intParam(PARAM_COOLDOWN).orElse(0),
                struct.intParam(PARAM_GLOBAL_COOLDOWN).orElse(DEFAULT_GLOBAL_COOLDOWN_TICKS)));
    }

    /** True when {@code other} names this ability, ignoring case. */
    public boolean isNamed(String other) {
        return name.equalsIgnoreCase(other);
    }
}
