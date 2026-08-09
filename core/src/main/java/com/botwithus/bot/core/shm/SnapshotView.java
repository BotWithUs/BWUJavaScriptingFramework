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
 *   int tick = s.serverTick();
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

    public long publishSeq()  { return seg.get(ValueLayout.JAVA_LONG, Layout.SNAP_PUBLISHSEQ_OFFSET); }
    public int  serverTick()  { return seg.get(ValueLayout.JAVA_INT,  Layout.SNAP_SERVERTICK_OFFSET); }
    public int  gameCycle()   { return seg.get(ValueLayout.JAVA_INT,  Layout.SNAP_GAMECYCLE_OFFSET); }
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
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_MAXHP_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.NPC_SPOTANIMID_OFFSET));
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
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PLAYER_COMBATLEVEL_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PLAYER_SPOTANIMID_OFFSET));
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

    // ------------------------------------------------------------------
    // Open sub-interfaces (v14+)
    //
    // The producer publishes a snapshot of jag::InterfaceManager's open-subs
    // hashmap each tick; membership = open. Hot polling (scripts looping on
    // "is interface X open?") used to pay a ~1-tick RPC round-trip per call —
    // now it's a sub-microsecond linear scan.
    // ------------------------------------------------------------------

    /** Number of live entries in {@code openIfaces} this tick. */
    public int openIfaceCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_OPENIFACECOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.OPEN_IFACE_CAP);
    }

    /** Returns the interface id at index {@code i} (0..openIfaceCount-1). */
    public int openIfaceAt(int i) {
        if (i < 0 || i >= openIfaceCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        return seg.get(ValueLayout.JAVA_INT,
                       Layout.SNAP_OPENIFACES_OFFSET + (long) i * 4);
    }

    /** True iff {@code ifaceId} appears in this tick's open-subs snapshot.
     *  Linear scan; the keyset is small (~20 ids) so this is faster than
     *  any data structure with constant overhead. */
    public boolean isInterfaceOpen(int ifaceId) {
        int count = openIfaceCount();
        for (int i = 0; i < count; i++) {
            if (seg.get(ValueLayout.JAVA_INT,
                        Layout.SNAP_OPENIFACES_OFFSET + (long) i * 4) == ifaceId) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Ground items (v15+)
    //
    // Mirrors the producer-side ObjStackList walk. Replaces the retired
    // query_ground_items RPC — scripts read the immutable snapshot and
    // apply their own spatial filters.
    // ------------------------------------------------------------------

    public int groundItemCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_GROUNDITEMCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.GROUND_ITEM_CAP);
    }

    public GroundItemEntry groundItemAt(int i) {
        if (i < 0 || i >= groundItemCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.SNAP_GROUNDITEMS_OFFSET + (long) i * Layout.GROUND_ITEM_ENTRY_SIZE;
        return new GroundItemEntry(
                seg.get(ValueLayout.JAVA_INT,   base + Layout.GROUND_ITEM_ITEMID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.GROUND_ITEM_QUANTITY_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.GROUND_ITEM_TILEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.GROUND_ITEM_TILEY_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.GROUND_ITEM_PLANE_OFFSET));
    }

    // ------------------------------------------------------------------
    // Projectiles (v17+)
    //
    // Mirrors the producer-side projectile-list walk. One row per in-flight
    // projectile; scripts read the immutable snapshot and apply their own
    // source/target/spatial filters.
    // ------------------------------------------------------------------

    public int projectileCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_PROJECTILECOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.PROJECTILE_CAP);
    }

    public ProjectileEntry projectileAt(int i) {
        if (i < 0 || i >= projectileCount()) {
            throw new IndexOutOfBoundsException(i);
        }
        long base = Layout.SNAP_PROJECTILES_OFFSET + (long) i * Layout.PROJECTILE_ENTRY_SIZE;
        return new ProjectileEntry(
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PROJECTILE_ID_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PROJECTILE_STARTCYCLE_OFFSET),
                seg.get(ValueLayout.JAVA_INT,   base + Layout.PROJECTILE_ENDCYCLE_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_SOURCEINDEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_SOURCETYPE_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_TARGETINDEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_TARGETTYPE_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_STARTTILEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_STARTTILEY_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_ENDTILEX_OFFSET),
                seg.get(ValueLayout.JAVA_SHORT, base + Layout.PROJECTILE_ENDTILEY_OFFSET),
                seg.get(ValueLayout.JAVA_BYTE,  base + Layout.PROJECTILE_PLANE_OFFSET));
    }

    // ------------------------------------------------------------------
    // Dynamic region (v19+)
    //
    // The client's instance chunk-descriptor grid. The grid stays in shared
    // memory: only the scalar header is read out, and tile lookups go through
    // the DynamicRegionView flyweight so a per-tile loop allocates nothing.
    // In a static scene the producer publishes dynChunkCount = 0 and leaves
    // the chunk bytes stale, so nothing here may read past the count.
    // ------------------------------------------------------------------

    /** The dynamic-region block's ten scalars. Cheap enough to read per tick. */
    public DynamicRegionEntry dynRegion() {
        long base = Layout.SNAP_DYNREGION_OFFSET;
        return new DynamicRegionEntry(
                seg.get(ValueLayout.JAVA_BYTE, base + Layout.DYNREGION_ISINSTANCE_OFFSET) != 0,
                seg.get(ValueLayout.JAVA_BYTE, base + Layout.DYNREGION_TRUNCATED_OFFSET) != 0,
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_SCENEMODE_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_ORIGINMAPX_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_ORIGINMAPY_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_MAXMAPX_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_MAXMAPY_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_GRIDW_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_GRIDH_OFFSET),
                seg.get(ValueLayout.JAVA_INT,  base + Layout.DYNREGION_REQUIREDCHUNKS_OFFSET));
    }

    /** Number of published chunk descriptors this tick, clamped to the wire cap. */
    public int dynChunkCount() {
        int n = seg.get(ValueLayout.JAVA_INT, Layout.SNAP_DYNCHUNKCOUNT_OFFSET);
        return n < 0 ? 0 : Math.min(n, Layout.DYN_CHUNK_CAP);
    }

    /** The block as the public {@code DynamicRegion} surface: header read now,
     *  grid left in place behind a slice sized to the published count. */
    public DynamicRegionView dynamicRegion() {
        int count = dynChunkCount();
        MemorySegment chunks = seg.asSlice(Layout.SNAP_DYNCHUNKS_OFFSET,
                                           (long) count * Integer.BYTES);
        return new DynamicRegionView(dynRegion(), chunks, count);
    }
}
