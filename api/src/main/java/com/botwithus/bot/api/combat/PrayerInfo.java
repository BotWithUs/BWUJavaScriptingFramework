package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.model.StructType;

/**
 * A prayer's definition, read from its cache struct. For a {@code TIERED_*} prayer it
 * describes the tier the local player's Prayer level selects.
 *
 * @param prayer        the book entry
 * @param structId      the struct the fields were read from (the tier's, for a tiered prayer)
 * @param name          display name ({@code COMBATV2_ABILITY_NAME})
 * @param levelRequired Prayer level needed ({@code COMBATV2_ABILITY_LEVEL_REQ})
 * @param drainRate     drain rate ({@code COMBATV2_PRAYER_DRAIN_RATE}), in the game's units
 * @param isMembers     members-only ({@code COMBATV2_ABILITY_IS_MEMBERS})
 * @param quickPrayerId its id in the quick-prayer set ({@code PRAYER_QUICK_PRAYER_ID})
 * @param graphicOn     sprite shown while on ({@code PRAYER_GRAPHIC_ON})
 * @param graphicOff    sprite shown while off ({@code PRAYER_GRAPHIC_OFF})
 */
public record PrayerInfo(Prayer prayer, int structId, String name, int levelRequired,
                         int drainRate, boolean isMembers, int quickPrayerId, int graphicOn,
                         int graphicOff) {

    static final int PARAM_NAME = Ability.PARAM_NAME;
    static final int PARAM_LEVEL = 2807;
    static final int PARAM_MEMBERS = 2809;
    static final int PARAM_DRAIN_RATE = 2945;
    static final int PARAM_QUICK_PRAYER_ID = 6670;
    static final int PARAM_GRAPHIC_ON = 735;
    static final int PARAM_GRAPHIC_OFF = 736;
    /** {@code COMBATV2_TIER1_LEVEL}; tier n's level is at {@code + 2(n-1)}, its struct one after. */
    static final int PARAM_TIER1_LEVEL = 2963;
    static final int TIERS = 3;
    private static final int UNKNOWN = -1;

    /** Reads {@code struct} as the definition of {@code prayer}. */
    static PrayerInfo fromStruct(Prayer prayer, StructType struct) {
        return new PrayerInfo(prayer, struct.id(),
                struct.stringParam(PARAM_NAME).orElse(prayer.name()),
                struct.intParam(PARAM_LEVEL).orElse(UNKNOWN),
                struct.intParam(PARAM_DRAIN_RATE).orElse(UNKNOWN),
                struct.intParam(PARAM_MEMBERS).orElse(0) != 0,
                struct.intParam(PARAM_QUICK_PRAYER_ID).orElse(UNKNOWN),
                struct.intParam(PARAM_GRAPHIC_ON).orElse(UNKNOWN),
                struct.intParam(PARAM_GRAPHIC_OFF).orElse(UNKNOWN));
    }
}
