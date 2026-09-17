package com.botwithus.bot.core.shm;

/**
 * One row of the scene-Location array in a snapshot. See
 * {@link Layout#LOCATION_ENTRY_SIZE} for the byte layout.
 *
 * <p>Rows arrive from two producer sites: direct LOCATION children of a
 * {@code LocationContainer.graph_node}, and COMBINED_LOCATION_SECTION
 * grandchildren one level deeper. The two paths share offsets where it's
 * safe; differences (interactId, animationId, deleted-flag) are normalised
 * by the producer so the consumer sees a single uniform row shape — flag
 * bit {@link Layout#LOC_FLAG_COMBINED_SECTION} tells which path the row
 * came from.</p>
 *
 * @param typeId       ConfigType id (location's "type id"); {@code -1} if not resolved
 * @param interactId   raw interact id for direct LOCATIONs; always {@code -1} on sections
 * @param animationId  current loc_animation id, or {@code -1} when not animating
 *                     (sections share their parent COMBINED_LOCATION's animation
 *                     and so always read {@code -1} here)
 * @param tileX        absolute world tile X
 * @param tileY        absolute world tile Y
 * @param plane        {@code 0..3}
 * @param shape        RT4 shape (0..22)
 * @param rotation     {@code 0..3}
 * @param flags        bitset; see {@link Layout#LOC_FLAG_HIDDEN},
 *                     {@link Layout#LOC_FLAG_COMBINED_SECTION},
 *                     {@link Layout#LOC_FLAG_DELETED}
 */
public record LocationEntry(
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
    public boolean isHidden() {
        return (flags & Layout.LOC_FLAG_HIDDEN) != 0;
    }

    public boolean isCombinedSection() {
        return (flags & Layout.LOC_FLAG_COMBINED_SECTION) != 0;
    }

    public boolean isDeleted() {
        return (flags & Layout.LOC_FLAG_DELETED) != 0;
    }
}
