package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one player at the producer's current tick.
 *
 * @param serverIndex    server-side player slot
 * @param tileX          absolute world tile X
 * @param tileY          absolute world tile Y
 * @param plane          {@code 0..3}
 * @param flags          bitset; bit 0 = moving (see {@link #isMoving()})
 * @param followingIndex server index of the follow target, or {@code -1}
 * @param animationId    current animation id, or {@code -1}
 * @param stanceId       current stance id
 * @param combatLevel    combat level, or {@code 0} if not computed
 * @param spotAnimId     first active spot anim (graphic) id, or {@code -1} if none
 * @param orientation    rendered facing (wire v21); {@link Orientation#unknown()} when not read
 * @param name           display name (wire v24), never {@code null}, exactly as the client holds
 *                       it (it can contain U+00A0, a no-break space, where the account name has
 *                       a space). <b>Empty means the name is not known yet</b>, not that the
 *                       player has none: the client writes the name while it decodes the
 *                       player's appearance, so a player who has only just come into view reads
 *                       {@code ""} for a while. When {@link #isNameTruncated()} is true this is
 *                       a prefix of the real name.
 */
public record Player(
        int serverIndex,
        int tileX,
        int tileY,
        int plane,
        int flags,
        int followingIndex,
        int animationId,
        int stanceId,
        int combatLevel,
        int spotAnimId,
        Orientation orientation,
        String name
) {

    private static final int FLAG_MOVING = 1;

    /** Bit 1 of {@link #flags()}; mirrors {@code kFlagNameTruncated} on the wire. */
    private static final int FLAG_NAME_TRUNCATED = 1 << 1;

    /** {@link #name()} when the name is not known yet. */
    public static final String NAME_UNKNOWN = "";

    public Player {
        if (name == null) {
            name = NAME_UNKNOWN;
        }
    }

    /** Builds a row with an unknown facing and name; kept so pre-v21 callers compile unchanged. */
    public Player(int serverIndex, int tileX, int tileY, int plane, int flags,
                  int followingIndex, int animationId, int stanceId, int combatLevel,
                  int spotAnimId) {
        this(serverIndex, tileX, tileY, plane, flags, followingIndex, animationId, stanceId,
                combatLevel, spotAnimId, Orientation.unknown());
    }

    /** Builds a row with an unknown name; kept so pre-v24 callers compile unchanged. */
    public Player(int serverIndex, int tileX, int tileY, int plane, int flags,
                  int followingIndex, int animationId, int stanceId, int combatLevel,
                  int spotAnimId, Orientation orientation) {
        this(serverIndex, tileX, tileY, plane, flags, followingIndex, animationId, stanceId,
                combatLevel, spotAnimId, orientation, NAME_UNKNOWN);
    }

    /** True when the name is known, i.e. {@link #name()} is not empty. */
    public boolean hasName() {
        return !name.isEmpty();
    }

    /**
     * True when {@link #name()} is a prefix: the client's name was longer than the 15 bytes the
     * wire carries. RS3 display names are at most 12 bytes, so this means something unusual. A
     * truncated name never matches an exact-name filter.
     */
    public boolean isNameTruncated() {
        return (flags & FLAG_NAME_TRUNCATED) != 0;
    }

    public boolean isMoving() {
        return (flags & FLAG_MOVING) != 0;
    }
}
