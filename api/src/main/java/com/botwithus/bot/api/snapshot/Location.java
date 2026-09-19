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
 * @param resolvedId   v20+. The morph-resolved ("multiloc") loc id — the appearance
 *                     definition carrying the name and the right-click options. A loc whose
 *                     look and menu are chosen by a var (a Range, a bonfire, a bank chest, a
 *                     construction hotspot, most instanced scenery) is published by the server
 *                     as a base id whose definition has an empty name and no options, so a
 *                     name or option lookup must use this id. Equals {@link #baseId()} when
 *                     the loc is not a multiloc — always usable, never a sentinel.
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
        int flags,
        int resolvedId
) {

    /**
     * Builds a row that is <em>not</em> a multiloc, deriving {@code resolvedId} from the base
     * id. This is the wire's own default — the producer publishes {@code resolvedId == baseId}
     * for every loc it does not transform — so it is the honest shape for a synthetic row.
     * Use the canonical constructor to model a loc that really does morph.
     */
    public Location(int typeId, int interactId, int animationId, int tileX, int tileY,
                    int plane, int shape, int rotation, int flags) {
        this(typeId, interactId, animationId, tileX, tileY, plane, shape, rotation, flags,
                (flags & FLAG_COMBINED_SECTION) != 0 ? typeId : interactId);
    }

    /**
     * The id the server sent for this row — what identity, hardcoded id sets and interaction
     * are keyed on. A combined section carries it in {@code typeId}; a direct LOCATION carries
     * it in {@code interactId}. Use this for everything except a name or option lookup, which
     * must use {@link #resolvedId()}.
     */
    public int baseId() {
        return isCombinedSection() ? typeId : interactId;
    }

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
