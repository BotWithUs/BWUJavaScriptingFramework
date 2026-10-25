package com.botwithus.bot.api.snapshot;

import java.util.List;

/**
 * Snapshot of the local (logged-in) player at the producer's current tick.
 * Returned by {@link GameSnapshot#self()}; {@code null} when the client is
 * not in-game.
 *
 * <p>Every field except the two health ones is read straight out of shared
 * memory. Health is not published on the wire — it lives in two varps, which
 * cost a pipe round-trip — so {@link GameSnapshot#self()} leaves it at
 * {@link #HEALTH_UNKNOWN} and {@code GameAPI.getLocalPlayer()} is the
 * accessor that fills it in. See {@link #hasHealth()}.</p>
 *
 * @param serverIndex    server-side player slot
 * @param combatLevel    combat level, or {@code 0} if not computed
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          {@code 0..3}
 * @param flags          bitset; bit 0 = moving
 * @param followingIndex server index of the follow target, or {@code -1}
 * @param animationId    current animation id, or {@code -1}
 * @param stanceId       current stance id
 * @param targetIndex    server index of the current target, or {@code -1}
 * @param targetType     target type discriminator, or {@code 0} for none
 * @param isMember       member-status flag from the producer
 * @param spotAnimId     first active spot anim (graphic) id, or {@code -1} if none
 * @param currentHealth  current life points, or {@link #HEALTH_UNKNOWN} when unfilled
 * @param maxHealth      maximum life points, or {@link #HEALTH_UNKNOWN} when unfilled
 * @param skills         live skills array; defensive copy taken on construction
 * @param orientation    rendered facing (wire v21); {@link Orientation#unknown()} when not read
 * @param runEnergy      run energy {@code 0..100} as the run orb shows it (wire v24), or
 *                       {@link #RUN_UNKNOWN}; see {@link #hasRunEnergy()}
 * @param runWeight      carried weight in kg as the client holds it (wire v24), or
 *                       {@link #RUN_UNKNOWN}. <b>Not independently verified:</b> the one live
 *                       check could not tell a right offset from a wrong one, so treat the value
 *                       as provisional until it is confirmed against the in-game weight display
 */
public record LocalPlayer(
        int serverIndex,
        int combatLevel,
        int tileX,
        int tileY,
        int plane,
        int flags,
        int followingIndex,
        int animationId,
        int stanceId,
        int targetIndex,
        int targetType,
        boolean isMember,
        int spotAnimId,
        int currentHealth,
        int maxHealth,
        List<Skill> skills,
        Orientation orientation,
        int runEnergy,
        int runWeight
) {

    private static final int FLAG_MOVING = 1;

    /**
     * Value {@link #currentHealth()} and {@link #maxHealth()} carry when no
     * one has read the backing varps — the sentinel the variable reads use
     * for "unset" as well, so a caller needs only one "no value" check.
     */
    public static final int HEALTH_UNKNOWN = -1;

    /**
     * Value {@link #runEnergy()} and {@link #runWeight()} carry when there is no reading: the
     * producer could not read it in-world, the client is not in the world (the wire holds
     * {@code 0} there, which this host reports as unknown so that {@code 0} always means an
     * empty run bar), or the record was built with a pre-v24 constructor.
     */
    public static final int RUN_UNKNOWN = -1;

    public LocalPlayer {
        skills = List.copyOf(skills);
    }

    /** Builds a record with unknown run state; kept so pre-v24 callers compile unchanged. */
    public LocalPlayer(int serverIndex, int combatLevel, int tileX, int tileY, int plane,
                       int flags, int followingIndex, int animationId, int stanceId,
                       int targetIndex, int targetType, boolean isMember, int spotAnimId,
                       int currentHealth, int maxHealth, List<Skill> skills,
                       Orientation orientation) {
        this(serverIndex, combatLevel, tileX, tileY, plane, flags, followingIndex, animationId,
                stanceId, targetIndex, targetType, isMember, spotAnimId, currentHealth,
                maxHealth, skills, orientation, RUN_UNKNOWN, RUN_UNKNOWN);
    }

    /** Builds a record with an unknown facing; kept so pre-v21 callers compile unchanged. */
    public LocalPlayer(int serverIndex, int combatLevel, int tileX, int tileY, int plane,
                       int flags, int followingIndex, int animationId, int stanceId,
                       int targetIndex, int targetType, boolean isMember, int spotAnimId,
                       int currentHealth, int maxHealth, List<Skill> skills) {
        this(serverIndex, combatLevel, tileX, tileY, plane, flags, followingIndex, animationId,
                stanceId, targetIndex, targetType, isMember, spotAnimId, currentHealth,
                maxHealth, skills, Orientation.unknown());
    }

    public boolean isMoving() {
        return (flags & FLAG_MOVING) != 0;
    }

    /**
     * True when both health fields carry a real reading. False on a record
     * taken straight from the snapshot, which never pays the varp read.
     */
    public boolean hasHealth() {
        return currentHealth != HEALTH_UNKNOWN && maxHealth != HEALTH_UNKNOWN;
    }

    /** True when {@link #runEnergy()} is a real reading rather than {@link #RUN_UNKNOWN}. */
    public boolean hasRunEnergy() {
        return runEnergy != RUN_UNKNOWN;
    }

    /**
     * Copy of this record carrying the supplied health reading. The seam the
     * varp-reading accessor uses to enrich a snapshot-built record without
     * every caller restating the other thirteen fields.
     */
    public LocalPlayer withHealth(int currentHealth, int maxHealth) {
        return new LocalPlayer(
                serverIndex,
                combatLevel,
                tileX,
                tileY,
                plane,
                flags,
                followingIndex,
                animationId,
                stanceId,
                targetIndex,
                targetType,
                isMember,
                spotAnimId,
                currentHealth,
                maxHealth,
                skills,
                orientation,
                runEnergy,
                runWeight);
    }
}
