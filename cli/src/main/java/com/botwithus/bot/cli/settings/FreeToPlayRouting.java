package com.botwithus.bot.cli.settings;

/**
 * Whether the world walker plans as a free-to-play account ({@code walking.freeToPlay}).
 *
 * <p>Off by default. Turning it on makes no difference to a members account: the
 * restriction applies only to a walk that starts while the account reads as not a
 * member.</p>
 */
public enum FreeToPlayRouting {
    /** Plan every walk as a member, as the walker always has. */
    OFF,
    /** Keep a free-to-play account off members routes and members land. */
    RESTRICT_WHEN_FREE_TO_PLAY;

    /** Whether walks are restricted when the account is free-to-play. */
    public boolean isRestricting() {
        return this == RESTRICT_WHEN_FREE_TO_PLAY;
    }
}
