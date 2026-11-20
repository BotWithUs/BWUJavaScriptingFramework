package com.botwithus.bot.api.world;

/**
 * One row of the client's world-select list ({@code get_world_list}).
 *
 * <p>{@link #flags()} is the client's raw bitfield. Four bits are decoded, from a live read
 * on 951-4: {@link #MEMBERS_FLAG}, {@link #SKILL_TOTAL_FLAG}, {@link #CLASSIC_ONLY_FLAG} and
 * {@link #EOC_ONLY_FLAG}. The others ({@code 0x2}, {@code 0x8}, {@code 0x10},
 * {@code 0x1000}, {@code 0x8000}) are seen but not understood, so they are left in
 * {@link #flags()} and nothing here names them.</p>
 *
 * @param id           the world number
 * @param flags        the raw flag bits
 * @param activity     the activity text the client shows
 * @param locationId   the location id the client shows (its override when set)
 * @param locationName the location name paired with {@code locationId}
 * @param population   the player count
 * @param ping         a <b>hypothesis</b> on the agent's side; read 0 on every world so far
 */
public record World(int id, int flags, String activity, int locationId, String locationName,
                    int population, int ping) {

    /** Members world. */
    public static final int MEMBERS_FLAG = 0x1;
    /** Skill-total world. */
    public static final int SKILL_TOTAL_FLAG = 0x80;
    /** Classic-combat-only world. */
    public static final int CLASSIC_ONLY_FLAG = 0x400000;
    /** EoC-combat-only world. */
    public static final int EOC_ONLY_FLAG = 0x800000;

    /** True for a members world. */
    public boolean isMembers() {
        return hasFlag(MEMBERS_FLAG);
    }

    /** True for a skill-total world. */
    public boolean isSkillTotal() {
        return hasFlag(SKILL_TOTAL_FLAG);
    }

    /** True for a world limited to Classic combat. */
    public boolean isClassicOnly() {
        return hasFlag(CLASSIC_ONLY_FLAG);
    }

    /** True for a world limited to EoC combat. */
    public boolean isEocOnly() {
        return hasFlag(EOC_ONLY_FLAG);
    }

    /** True when every bit of {@code flag} is set. */
    public boolean hasFlag(int flag) {
        return (flags & flag) == flag;
    }
}
