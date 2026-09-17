package com.botwithus.bot.api.snapshot;

/**
 * Snapshot of one scene Location (door / tree / rock / bank / scenery tile)
 * at the producer's current tick.
 *
 * <p>Rows come from two producer-side iteration paths — direct LOCATION
 * children and COMBINED_LOCATION_SECTION grandchildren. The two paths share
 * a field shape; {@link #isCombinedSection()} distinguishes them so callers
 * filtering for parent vs child can split on it. Sections share their
 * parent COMBINED_LOCATION's lifetime, animation, and interact id — fields
 * that don't apply on sections read {@code -1}.</p>
 *
 * @param typeId       ConfigType id, or {@code -1} if not resolved
 * @param interactId   raw interact id (direct LOCATIONs only); always {@code -1} on sections
 * @param animationId  current animation id, or {@code -1} when not animating
 *                     (always {@code -1} on sections)
 * @param tileX        absolute world tile X
 * @param tileY        absolute world tile Y
 * @param plane        {@code 0..3}
 * @param shape        RT4 shape (0..22)
 * @param rotation     {@code 0..3}
 * @param flags        bitset; inspect via {@link #isHidden()},
 *                     {@link #isCombinedSection()}, {@link #isDeleted()}
 */
public record Location(
        int typeId,
        int interactId,
        int animationId,
        int tileX,
        int tileY,
        int plane,
        int shape,
        int rotation,
        int flags
) {

    /** Bit 0; mirrors {@code LOC_FLAG_HIDDEN} on the wire. */
    private static final int FLAG_HIDDEN            = 1 << 0;
    /** Bit 1; mirrors {@code LOC_FLAG_COMBINED_SECTION} on the wire. */
    private static final int FLAG_COMBINED_SECTION  = 1 << 1;
    /** Bit 2; mirrors {@code LOC_FLAG_DELETED} on the wire. */
    private static final int FLAG_DELETED           = 1 << 2;

    public boolean isHidden() {
        return (flags & FLAG_HIDDEN) != 0;
    }

    public boolean isCombinedSection() {
        return (flags & FLAG_COMBINED_SECTION) != 0;
    }

    public boolean isDeleted() {
        return (flags & FLAG_DELETED) != 0;
    }
}
