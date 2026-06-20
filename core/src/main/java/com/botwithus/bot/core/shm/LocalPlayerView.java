package com.botwithus.bot.core.shm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Read-only view over the {@code self} block of a snapshot. Holds a slice
 * of the underlying mapping; valid until the {@link SharedRegion} is closed
 * or the snapshot buffer it points into is overwritten by the next publish.
 *
 * <p>Callers iterating skills should treat this as a transient view —
 * inside a single tick the data is stable, but across {@link Snapshot}
 * polls the writer may flip the front buffer and overwrite the slice we
 * sliced from.</p>
 */
public final class LocalPlayerView {

    private final MemorySegment seg;

    LocalPlayerView(MemorySegment seg) {
        this.seg = seg;
    }

    public int  serverIndex()    { return seg.get(ValueLayout.JAVA_INT,   Layout.LP_SERVERINDEX_OFFSET); }
    public int  combatLevel()    { return seg.get(ValueLayout.JAVA_INT,   Layout.LP_COMBATLEVEL_OFFSET); }
    public int  tileX()          { return seg.get(ValueLayout.JAVA_SHORT, Layout.LP_TILEX_OFFSET); }
    public int  tileY()          { return seg.get(ValueLayout.JAVA_SHORT, Layout.LP_TILEY_OFFSET); }
    public int  plane()          { return seg.get(ValueLayout.JAVA_BYTE,  Layout.LP_PLANE_OFFSET); }
    public int  flags()          { return seg.get(ValueLayout.JAVA_BYTE,  Layout.LP_FLAGS_OFFSET) & 0xFF; }
    public int  followingIndex() { return seg.get(ValueLayout.JAVA_SHORT, Layout.LP_FOLLOWINGINDEX_OFFSET); }
    public int  animationId()    { return seg.get(ValueLayout.JAVA_INT,   Layout.LP_ANIMATIONID_OFFSET); }
    public int  stanceId()       { return seg.get(ValueLayout.JAVA_INT,   Layout.LP_STANCEID_OFFSET); }
    public int  targetIndex()    { return seg.get(ValueLayout.JAVA_SHORT, Layout.LP_TARGETINDEX_OFFSET); }
    public int  targetType()     { return seg.get(ValueLayout.JAVA_BYTE,  Layout.LP_TARGETTYPE_OFFSET); }
    public boolean isMember()    { return seg.get(ValueLayout.JAVA_BYTE,  Layout.LP_ISMEMBER_OFFSET) != 0; }
    public int  spotAnimId()     { return seg.get(ValueLayout.JAVA_INT,   Layout.LP_SPOTANIMID_OFFSET); }

    public boolean isMoving()    { return (flags() & Layout.FLAG_MOVING) != 0; }

    /** Live skill count; {@code 0..skillCount)} are valid indices for {@link #skill(int)}. */
    public int skillCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.LP_SKILLCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.SKILL_CAP);
    }

    public SkillEntry skill(int i) {
        if (i < 0 || i >= skillCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.LP_SKILLS_OFFSET + (long) i * Layout.SKILL_ENTRY_SIZE;
        return new SkillEntry(
                seg.get(ValueLayout.JAVA_INT, base + Layout.SKILL_TYPEID_OFFSET),
                seg.get(ValueLayout.JAVA_INT, base + Layout.SKILL_EXPERIENCE_OFFSET),
                seg.get(ValueLayout.JAVA_INT, base + Layout.SKILL_ACTUALLEVEL_OFFSET),
                seg.get(ValueLayout.JAVA_INT, base + Layout.SKILL_BOOSTEDLEVEL_OFFSET));
    }
}
