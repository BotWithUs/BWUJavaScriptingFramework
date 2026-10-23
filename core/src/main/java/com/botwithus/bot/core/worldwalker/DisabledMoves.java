package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.snapshot.LocalPlayer;

/**
 * The {@code disabledMoves} mask a walk or query hands the planner, mirroring the
 * argument of {@code ww_executor_run_ex} and {@code ww_query_moves}.
 *
 * <p>Only the free-to-play bit is spelled here, because it is the only part the
 * host sets. The library defines it as a reserved bit of the same mask rather than
 * a movement category: with it set the planner plans as a free-to-play account,
 * refusing members transitions and members land. A clear mask is exactly the
 * behaviour of the plain entry points, which is why {@link #NONE} is sent through
 * them rather than through the extended ones.</p>
 *
 * <p>Two library versions cannot honour the bit, and neither says so: a
 * {@code worldwalker.dll} with no extended entry points (the host then walks
 * unrestricted and logs a warning once, see {@link MovesEntry}), and one that has
 * the entry points but predates the bit, which ignores it and plans as a
 * member.</p>
 *
 * @param mask the raw mask, bit for bit as the C ABI takes it
 */
public record DisabledMoves(int mask) {

    /** {@code WW_RESTRICT_FREE_TO_PLAY}, {@code (1u << 31)} in the C header. */
    public static final int RESTRICT_FREE_TO_PLAY_BIT = 1 << 31;

    /** Nothing disabled: today's routing. */
    public static final DisabledMoves NONE = new DisabledMoves(0);

    /** Plan as a free-to-play account. */
    public static final DisabledMoves FREE_TO_PLAY = new DisabledMoves(RESTRICT_FREE_TO_PLAY_BIT);

    /**
     * The mask for a walk starting now.
     *
     * <p>Restricts only when the host setting is on <em>and</em> the snapshot says
     * the account is not a member. With no local player to read, the membership is
     * unknown and the walk is planned unrestricted, which is what it was before the
     * setting existed; a walk cannot start without a position anyway.</p>
     *
     * @param isRestrictionOn the host's free-to-play routing setting
     * @param self            the local player at walk start, or {@code null}
     */
    public static DisabledMoves forWalk(boolean isRestrictionOn, LocalPlayer self) {
        if (!isRestrictionOn || self == null || self.isMember()) {
            return NONE;
        }
        return FREE_TO_PLAY;
    }

    /** Whether nothing is disabled, so the plain entry point is the right call. */
    public boolean isNone() {
        return mask == 0;
    }

    /** Whether the free-to-play bit is set. */
    public boolean isFreeToPlay() {
        return (mask & RESTRICT_FREE_TO_PLAY_BIT) != 0;
    }
}
