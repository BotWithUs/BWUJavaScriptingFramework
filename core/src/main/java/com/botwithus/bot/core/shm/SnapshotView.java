package com.botwithus.bot.core.shm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Typed accessor over a single snapshot buffer in the shared region.
 *
 * <p>Lifetime: instances are valid only as long as the writer hasn't
 * flipped {@code frontIdx} a second time after this view was acquired. The
 * standard pattern is:
 * <pre>{@code
 *   SnapshotView s = region.snapshot();   // read frontIdx, slice that buffer
 *   long tick = s.tickId();
 *   // ...iterate s.npcAt() etc within one tick worth of work...
 * }</pre>
 * Don't cache a {@link SnapshotView} across ticks.</p>
 *
 * <p>For NPCs/players/inventory the entry types are records constructed on
 * read. At 1024 NPCs allocating per tick costs us a few KB of garbage — if
 * that ever shows up in profiles we can switch to flyweight views the way
 * {@link LocalPlayerView} does.</p>
 */
public final class SnapshotView {

    private final MemorySegment seg;

    public SnapshotView(MemorySegment seg) {
        this.seg = seg;
    }

    public long tickId()      { return seg.get(ValueLayout.JAVA_LONG, Layout.SNAP_TICKID_OFFSET); }
    public int  gameState()   { return seg.get(ValueLayout.JAVA_INT,  Layout.SNAP_GAMESTATE_OFFSET); }
    public int  ownIndex()    { return seg.get(ValueLayout.JAVA_INT,  Layout.SNAP_OWNINDEX_OFFSET); }
    public int  rootIfaceId() { return seg.get(ValueLayout.JAVA_INT,  Layout.SNAP_ROOTIFACEID_OFFSET); }

    public LocalPlayerView self() {
        return new LocalPlayerView(seg.asSlice(Layout.SNAP_SELF_OFFSET, Layout.LOCAL_PLAYER_SIZE));
    }

    // ------------------------------------------------------------------
    // NPC array
    // ------------------------------------------------------------------

    public int npcCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_NPCCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.NPC_CAP);
    }

    public NpcEntry npcAt(int i) {
        if (i < 0 || i >= npcCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.SNAP_NPCS_OFFSET + (long) i * Layout.NPC_ENTRY_SIZE;
        return new NpcEntry(
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_SERVERINDEX_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_TYPEID_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.NPC_TILEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.NPC_TILEY_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.NPC_PLANE_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.NPC_FLAGS_OFFSET) & 0xFF,
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.NPC_FOLLOWINGINDEX_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_ANIMATIONID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_STANCEID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_HP_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_MAXHP_OFFSET));
    }

    // ------------------------------------------------------------------
    // Player array
    // ------------------------------------------------------------------

    public int playerCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_PLAYERCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.PLAYER_CAP);
    }

    public PlayerEntry playerAt(int i) {
        if (i < 0 || i >= playerCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.SNAP_PLAYERS_OFFSET + (long) i * Layout.PLAYER_ENTRY_SIZE;
        return new PlayerEntry(
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PLAYER_SERVERINDEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PLAYER_TILEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PLAYER_TILEY_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.PLAYER_PLANE_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.PLAYER_FLAGS_OFFSET) & 0xFF,
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PLAYER_FOLLOWINGINDEX_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PLAYER_ANIMATIONID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PLAYER_STANCEID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PLAYER_COMBATLEVEL_OFFSET));
    }

    // ------------------------------------------------------------------
    // Location array
    // ------------------------------------------------------------------

    public int locationCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_LOCATIONCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.LOCATION_CAP);
    }

    public LocationEntry locationAt(int i) {
        if (i < 0 || i >= locationCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.SNAP_LOCATIONS_OFFSET + (long) i * Layout.LOCATION_ENTRY_SIZE;
        return new LocationEntry(
                seg.get(ValueLayout.JAVA_INT,   base + Layout.LOC_TYPEID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.LOC_INTERACTID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.LOC_ANIMATIONID_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.LOC_TILEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.LOC_TILEY_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.LOC_PLANE_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.LOC_SHAPE_OFFSET) & 0xFF,
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.LOC_ROTATION_OFFSET) & 0xFF,
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.LOC_FLAGS_OFFSET) & 0xFF);
    }

    /**
     * Producer-side counter bumped whenever the loaded_map_squares vector
     * identity changes — i.e. the streamed scene shape has changed (region
     * crossing, login, etc). Consumers use the value as a cache key on any
     * per-region structure derived from snapshot data.
     */
    public int sceneVersion() {
        return seg.get(ValueLayout.JAVA_INT,
                       Layout.SNAP_PRODUCER_OFFSET + Layout.PRODUCER_SCENEVERSION_OFFSET);
    }

    // ------------------------------------------------------------------
    // Inventory array + flat item buffer
    // ------------------------------------------------------------------

    public int inventoryCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_INVENTORYCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.INVENTORY_CAP);
    }

    public int invId(int i)        { return readInvHeader(i, Layout.INV_HEADER_INVID_OFFSET, false); }
    public int slotCount(int i)    { return readInvHeader(i, Layout.INV_HEADER_SLOTCOUNT_OFFSET, true) & 0xFFFF; }
    public int firstItemIdx(int i) { return readInvHeader(i, Layout.INV_HEADER_FIRSTITEMIDX_OFFSET, true) & 0xFFFF; }

    private int readInvHeader(int i, int fieldOffset, boolean shortField) {
        if (i < 0 || i >= inventoryCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.SNAP_INVENTORIES_OFFSET + (long) i * Layout.INV_HEADER_SIZE;
        if (shortField) {
            return seg.get(ValueLayout.JAVA_SHORT, base + fieldOffset);
        }
        return seg.get(ValueLayout.JAVA_INT, base + fieldOffset);
    }

    public int invItemCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_INVITEMCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.INVENTORY_ITEM_CAP);
    }

    public int itemIdAt(int flatIdx) {
        if (flatIdx < 0 || flatIdx >= invItemCount()) {
            throw new IndexOutOfBoundsException(flatIdx);
        }
        long base = Layout.SNAP_INVITEMS_OFFSET + (long) flatIdx * Layout.INV_ITEM_SIZE;
        return seg.get(ValueLayout.JAVA_INT, base + Layout.INV_ITEM_ITEMID_OFFSET);
    }

    public int itemQtyAt(int flatIdx) {
        if (flatIdx < 0 || flatIdx >= invItemCount()) {
            throw new IndexOutOfBoundsException(flatIdx);
        }
        long base = Layout.SNAP_INVITEMS_OFFSET + (long) flatIdx * Layout.INV_ITEM_SIZE;
        return seg.get(ValueLayout.JAVA_INT, base + Layout.INV_ITEM_QUANTITY_OFFSET);
    }
}
