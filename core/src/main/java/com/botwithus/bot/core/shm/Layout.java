package com.botwithus.bot.core.shm;

/**
 * Wire-stable layout constants mirroring NXTLibrary/src/ipc/SharedLayout.h
 * and Events.h. Bumping {@link #PROTOCOL_VERSION} here is a hard
 * requirement when the C++ side bumps its own — the readers in this package
 * verify it and refuse to bind to an older or newer mapping.
 *
 * <p>All {@code _OFFSET} constants are byte offsets within the field's
 * containing struct; all {@code _SIZE} constants are byte sizes. Field
 * naming follows the C++ struct field names so a side-by-side diff with
 * SharedLayout.h is visually clean.</p>
 */
public final class Layout {

    private Layout() {}

    // ------------------------------------------------------------------
    // Top-level constants
    // ------------------------------------------------------------------

    /** {@code 'N','X','T','S'} read as little-endian u32. */
    public static final int MAGIC = 0x5354584E;

    /** Wire protocol version. Must equal {@code kProtocolVersion} in NXTLibrary's SharedLayout.h.
     *  v19 appended the {@code dynRegion} tail block — the client's dynamic-region (instance)
     *  chunk-descriptor grid. RS3 assembles instances (player-owned houses, Dungeoneering floors,
     *  boss rooms) by stamping 8x8-tile chunks copied out of ordinary static regions, driven by a
     *  server-supplied table; publishing that table lets a consumer map an instance tile back to
     *  the static tile it was copied from, which is what a static-baked navigation layer needs to
     *  path inside an instance. The block is a 36-byte scalar header
     *  ({@link #SNAP_DYNREGION_OFFSET}) plus a count and a {@link #DYN_CHUNK_CAP}-entry array of
     *  raw packed u32 descriptors. v18 had consumed the last tail pad, so there was nowhere to
     *  hide the new fields — the snapshot grew and every reader must be rebuilt (hard version
     *  bump).
     *  v18 made the snapshot's three time bases separately readable and honestly named. The u64 at
     *  offset 0 was {@code tickId} but is neither a tick nor the client's cycle counter — it is the
     *  producer's own publish counter, so it is now {@code publishSeq}. Alongside it the snapshot
     *  gained {@code serverTick} (the 600ms clock scripts pace against) and {@code gameCycle} (the
     *  client's 20ms counter, which is what {@code ProjectileEntry.startCycle/endCycle} are stamped
     *  in — previously unavailable without a {@code get_game_cycle} RPC per tick). Both new fields
     *  reuse slots that were already reserved padding, so {@link #SNAPSHOT_SIZE} and every
     *  downstream offset are unchanged from v17 — but a v17 reader would decode the new fields as
     *  the pad it was told to ignore, so it is still a hard version bump.
     *  v17 added the {@code projectiles[]} tail block — per-tick snapshot of every in-flight
     *  projectile (thrown spell/arrow graphic travelling source→target), walked from the producer's
     *  projectile list. Each row carries the graphic id, the launch/land game-cycle stamps, the
     *  source/target entity server index + type tag (index -1 when that end is a fixed tile), and the
     *  start/end world tiles. Snapshot-array only — no projectile event on the ring. Appending the
     *  block shifted the total snapshot size (hard version bump).
     *  v16 added a per-entity {@code spotAnimId} field to NpcEntry / PlayerEntry / LocalPlayer —
     *  the first active spot animation (graphic) playing on that entity this tick, or -1. Entities
     *  can carry several concurrent spot anims; this field surfaces only the first, while the
     *  {@code EVT_SPOT_ANIM} event reports every newly-started one. Growing the three records
     *  shifted every downstream snapshot offset (hard version bump).
     *  v15 added the {@code groundItems[]} tail block — per-tick snapshot of every alive
     *  ground-item stack within the loaded-scene tile bounds. Pairs with the host-side
     *  rewire of GroundItems and SceneObjects facades onto the SHM snapshot (parity with
     *  NPCs / Players), and retires {@code query_locations} / {@code query_ground_items}
     *  RPCs.
     *  v14 added the {@code openIfaces[]} tail block — a per-tick snapshot of every entry in
     *  jag::InterfaceManager's open-subs hashmap, so {@code isInterfaceOpen}-style checks no
     *  longer pay a per-call RPC round-trip.
     *  v13 dropped the per-interface {@code ifaceVersions[]} array; interface state is read
     *  fresh on demand via RPC rather than cached behind an invalidation token. */
    public static final int PROTOCOL_VERSION = 19;

    /** Mapping name prefix; appended with the target game-process pid. */
    public static final String MAPPING_NAME_PREFIX = "Local\\nxt_snapshot_";

    // ------------------------------------------------------------------
    // Caps
    // ------------------------------------------------------------------

    public static final int NPC_CAP            = 1024;
    public static final int PLAYER_CAP         = 2048;
    public static final int LOCATION_CAP       = 8192;
    public static final int SKILL_CAP          = 32;
    public static final int INVENTORY_CAP      = 32;
    public static final int INVENTORY_ITEM_CAP = 2048;
    /** Mirrors {@code kOpenIfaceCap} in SharedLayout.h. Snapshot of the
     *  open-sub-interfaces hashmap; live counts are typically <20. */
    public static final int OPEN_IFACE_CAP     = 64;
    /** Mirrors {@code kGroundItemCap} in SharedLayout.h. Cap on the per-tick
     *  ground-item array; matches {@link #NPC_CAP} for symmetry, costs 16 KB
     *  per buffer at {@link #GROUND_ITEM_ENTRY_SIZE} per row. */
    public static final int GROUND_ITEM_CAP    = 1024;
    /** Mirrors {@code kProjectileCap} in SharedLayout.h. Cap on the per-tick
     *  in-flight projectile array; live counts are sparse (a barrage volley tops
     *  out around a dozen), costs 8 KB per buffer at {@link #PROJECTILE_ENTRY_SIZE}
     *  per row. */
    public static final int PROJECTILE_CAP     = 256;
    /** Mirrors {@code kDynChunkCap} in SharedLayout.h. Cap on the dynamic-region
     *  chunk-descriptor grid: 4 planes x 64 x 64 chunks, 64 KB per buffer. Mode 6
     *  (the only size class measured live) needs {@code 4*32*32 = 4096}; the
     *  headroom covers the unmeasured modes 4/5/7, whose grid dims arrive as raw
     *  u8s off the wire. Sized deliberately large because a truncated grid
     *  publishes ZERO chunks and therefore reads as a static scene — a silent
     *  no-op is the worst failure this block can have. */
    public static final int DYN_CHUNK_CAP      = 16384;

    /** Bit flag shared between NpcEntry and PlayerEntry. */
    public static final int FLAG_MOVING = 1;

    // ------------------------------------------------------------------
    // LocationEntry flag bits (mirrors kLocFlag* in SharedLayout.h)
    // ------------------------------------------------------------------

    /** Set when the producer observed the Location's hidden byte non-zero. */
    public static final int LOC_FLAG_HIDDEN            = 1 << 0;
    /** Set on rows emitted from a COMBINED_LOCATION_SECTION (vs a direct LOCATION). */
    public static final int LOC_FLAG_COMBINED_SECTION  = 1 << 1;
    /** Set when the producer observed loc_deleted non-zero (direct LOCATIONs only). */
    public static final int LOC_FLAG_DELETED           = 1 << 2;

    // ------------------------------------------------------------------
    // SharedHeader
    // ------------------------------------------------------------------

    public static final int HEADER_SIZE                = 64;

    public static final int HEADER_MAGIC_OFFSET        = 0;    // u8[4]
    public static final int HEADER_VERSION_OFFSET      = 4;    // u32
    public static final int HEADER_HEADERSIZE_OFFSET   = 8;    // u32
    public static final int HEADER_LAYOUTID_OFFSET     = 12;   // u32
    public static final int HEADER_SNAPSHOTSIZE_OFFSET = 16;   // u32
    public static final int HEADER_SNAPSHOTOFF0_OFFSET = 20;   // u32
    public static final int HEADER_SNAPSHOTOFF1_OFFSET = 24;   // u32
    public static final int HEADER_RINGOFF_OFFSET      = 28;   // u32
    public static final int HEADER_RINGSIZE_OFFSET     = 32;   // u32
    public static final int HEADER_FRONTIDX_OFFSET     = 36;   // i32  (acquire-load on the reader)
    public static final int HEADER_TARGETPID_OFFSET    = 40;   // u64

    // ------------------------------------------------------------------
    // NpcEntry  (36 bytes)
    // ------------------------------------------------------------------

    public static final int NPC_ENTRY_SIZE = 36;

    public static final int NPC_SERVERINDEX_OFFSET    = 0;    // i32
    public static final int NPC_TYPEID_OFFSET         = 4;    // i32
    public static final int NPC_TILEX_OFFSET          = 8;    // i16
    public static final int NPC_TILEY_OFFSET          = 10;   // i16
    public static final int NPC_PLANE_OFFSET          = 12;   // i8
    public static final int NPC_FLAGS_OFFSET          = 13;   // u8
    public static final int NPC_FOLLOWINGINDEX_OFFSET = 14;   // i16
    public static final int NPC_ANIMATIONID_OFFSET    = 16;   // i32
    public static final int NPC_STANCEID_OFFSET       = 20;   // i32
    public static final int NPC_HP_OFFSET             = 24;   // i32
    public static final int NPC_MAXHP_OFFSET          = 28;   // i32
    public static final int NPC_SPOTANIMID_OFFSET     = 32;   // i32  first active spot anim id; -1 if none

    // ------------------------------------------------------------------
    // LocationEntry (20 bytes) — mirrors ipc::LocationEntry in SharedLayout.h.
    // typeId/interactId/animationId are -1 when not applicable. interactId is
    // always -1 on COMBINED_LOCATION_SECTION rows (see Location.h::InteractId).
    // ------------------------------------------------------------------

    public static final int LOCATION_ENTRY_SIZE = 20;

    public static final int LOC_TYPEID_OFFSET      = 0;    // i32
    public static final int LOC_INTERACTID_OFFSET  = 4;    // i32
    public static final int LOC_ANIMATIONID_OFFSET = 8;    // i32
    public static final int LOC_TILEX_OFFSET       = 12;   // i16
    public static final int LOC_TILEY_OFFSET       = 14;   // i16
    public static final int LOC_PLANE_OFFSET       = 16;   // i8
    public static final int LOC_SHAPE_OFFSET       = 17;   // u8
    public static final int LOC_ROTATION_OFFSET    = 18;   // u8
    public static final int LOC_FLAGS_OFFSET       = 19;   // u8

    // ------------------------------------------------------------------
    // GroundItemEntry (16 bytes) — mirrors ipc::GroundItemEntry in
    // SharedLayout.h. One row per alive ObjEntry the producer found in the
    // ObjStackList (v15+). itemId is the cache item type, quantity is the
    // stack size (1 for non-stackables).
    // ------------------------------------------------------------------

    public static final int GROUND_ITEM_ENTRY_SIZE = 16;

    public static final int GROUND_ITEM_ITEMID_OFFSET   = 0;    // i32
    public static final int GROUND_ITEM_QUANTITY_OFFSET = 4;    // i32
    public static final int GROUND_ITEM_TILEX_OFFSET    = 8;    // i16
    public static final int GROUND_ITEM_TILEY_OFFSET    = 10;   // i16
    public static final int GROUND_ITEM_PLANE_OFFSET    = 12;   // i8
    // bytes 13..15 are trailing pad; not accessed

    // ------------------------------------------------------------------
    // ProjectileEntry (32 bytes) — mirrors ipc::ProjectileEntry in
    // SharedLayout.h. One row per in-flight projectile (v17+). sourceIndex /
    // targetIndex are the server indices of the entity at each end, -1 when that
    // end is a fixed tile; sourceType / targetType are the engine's raw
    // entity-type tags. startCycle / endCycle are the game-cycle stamps
    // bracketing the flight. Tile coords are absolute world tiles.
    // ------------------------------------------------------------------

    public static final int PROJECTILE_ENTRY_SIZE = 32;

    public static final int PROJECTILE_ID_OFFSET          = 0;    // i32  graphic id
    public static final int PROJECTILE_STARTCYCLE_OFFSET  = 4;    // i32
    public static final int PROJECTILE_ENDCYCLE_OFFSET     = 8;    // i32
    public static final int PROJECTILE_SOURCEINDEX_OFFSET = 12;   // i16  -1 if tile-anchored
    public static final int PROJECTILE_SOURCETYPE_OFFSET  = 14;   // i16
    public static final int PROJECTILE_TARGETINDEX_OFFSET = 16;   // i16  -1 if tile target
    public static final int PROJECTILE_TARGETTYPE_OFFSET  = 18;   // i16
    public static final int PROJECTILE_STARTTILEX_OFFSET  = 20;   // i16
    public static final int PROJECTILE_STARTTILEY_OFFSET  = 22;   // i16
    public static final int PROJECTILE_ENDTILEX_OFFSET    = 24;   // i16
    public static final int PROJECTILE_ENDTILEY_OFFSET    = 26;   // i16
    public static final int PROJECTILE_PLANE_OFFSET       = 28;   // i8
    // bytes 29..31 are trailing pad; not accessed

    // ------------------------------------------------------------------
    // PlayerEntry (28 bytes)
    // ------------------------------------------------------------------

    public static final int PLAYER_ENTRY_SIZE = 28;

    public static final int PLAYER_SERVERINDEX_OFFSET    = 0;    // i32
    public static final int PLAYER_TILEX_OFFSET          = 4;    // i16
    public static final int PLAYER_TILEY_OFFSET          = 6;    // i16
    public static final int PLAYER_PLANE_OFFSET          = 8;    // i8
    public static final int PLAYER_FLAGS_OFFSET          = 9;    // u8
    public static final int PLAYER_FOLLOWINGINDEX_OFFSET = 10;   // i16
    public static final int PLAYER_ANIMATIONID_OFFSET    = 12;   // i32
    public static final int PLAYER_STANCEID_OFFSET       = 16;   // i32
    public static final int PLAYER_COMBATLEVEL_OFFSET    = 20;   // i32
    public static final int PLAYER_SPOTANIMID_OFFSET     = 24;   // i32  first active spot anim id; -1 if none

    // ------------------------------------------------------------------
    // SkillEntry (16 bytes)
    // ------------------------------------------------------------------

    public static final int SKILL_ENTRY_SIZE = 16;

    public static final int SKILL_TYPEID_OFFSET       = 0;
    public static final int SKILL_EXPERIENCE_OFFSET   = 4;
    public static final int SKILL_ACTUALLEVEL_OFFSET  = 8;
    public static final int SKILL_BOOSTEDLEVEL_OFFSET = 12;

    // ------------------------------------------------------------------
    // LocalPlayer (40 + 32*16 = 552 bytes)
    // ------------------------------------------------------------------

    public static final int LOCAL_PLAYER_SIZE = 552;

    public static final int LP_SERVERINDEX_OFFSET     = 0;
    public static final int LP_COMBATLEVEL_OFFSET     = 4;
    public static final int LP_TILEX_OFFSET           = 8;
    public static final int LP_TILEY_OFFSET           = 10;
    public static final int LP_PLANE_OFFSET           = 12;
    public static final int LP_FLAGS_OFFSET           = 13;
    public static final int LP_FOLLOWINGINDEX_OFFSET  = 14;
    public static final int LP_ANIMATIONID_OFFSET     = 16;
    public static final int LP_STANCEID_OFFSET        = 20;
    public static final int LP_TARGETINDEX_OFFSET     = 24;
    public static final int LP_TARGETTYPE_OFFSET      = 26;
    public static final int LP_ISMEMBER_OFFSET        = 27;
    public static final int LP_SPOTANIMID_OFFSET      = 28;   // i32  first active spot anim id; -1 if none
    // Slot at +32 is _pad0 (u32) — keeps sizeof(LocalPlayer) a multiple of 8 so
    // the producer block downstream stays 8-aligned. Not accessed; see
    // SharedLayout.h LocalPlayer::_pad0 for rationale.
    public static final int LP_SKILLCOUNT_OFFSET      = 36;
    public static final int LP_SKILLS_OFFSET          = 40;

    // ------------------------------------------------------------------
    // InventoryItem (8) and InventoryHeader (8)
    // ------------------------------------------------------------------

    public static final int INV_ITEM_SIZE          = 8;
    public static final int INV_ITEM_ITEMID_OFFSET   = 0;
    public static final int INV_ITEM_QUANTITY_OFFSET = 4;

    public static final int INV_HEADER_SIZE                = 8;
    public static final int INV_HEADER_INVID_OFFSET        = 0;
    public static final int INV_HEADER_SLOTCOUNT_OFFSET    = 4;
    public static final int INV_HEADER_FIRSTITEMIDX_OFFSET = 6;

    // ------------------------------------------------------------------
    // Snapshot — laid out as a sum of preceding regions; each accessor
    // computes its offset from these constants rather than hard-coding
    // them, so a layout audit can read the formulae directly.
    // ------------------------------------------------------------------

    /** Producer's publish counter (u64), +1 per ~20ms client main-loop iteration. A liveness
     *  signal only — not a tick, and not comparable to {@link #SNAP_GAMECYCLE_OFFSET}. Named
     *  {@code tickId} through v17, which is why anything pacing off it ran ~30x fast. */
    public static final int SNAP_PUBLISHSEQ_OFFSET   = 0;     // u64
    public static final int SNAP_GAMESTATE_OFFSET    = 8;     // i32
    public static final int SNAP_OWNINDEX_OFFSET     = 12;    // i32
    /** Active root interface id (e.g. 1477 in resizable HUD mode); -1 when no root mounted. */
    public static final int SNAP_ROOTIFACEID_OFFSET  = 16;    // i32
    /** Server-tick counter (i32, 600ms cadence) — the clock scripts should pace against.
     *  {@code -1} until the producer observes one. Occupies what was {@code _reserved0}
     *  through v17: the slot exists either way to keep the producer block 8-aligned, and
     *  v18 gave the padding a job. See SharedLayout.h {@code Snapshot::serverTick}. */
    public static final int SNAP_SERVERTICK_OFFSET   = 20;    // i32
    public static final int SNAP_SELF_OFFSET         = 24;    // LocalPlayer

    public static final int SNAP_NPCCOUNT_OFFSET   = SNAP_SELF_OFFSET + LOCAL_PLAYER_SIZE;
    public static final int SNAP_NPCS_OFFSET       = SNAP_NPCCOUNT_OFFSET + 4;

    public static final int SNAP_PLAYERCOUNT_OFFSET = SNAP_NPCS_OFFSET + NPC_CAP * NPC_ENTRY_SIZE;
    public static final int SNAP_PLAYERS_OFFSET     = SNAP_PLAYERCOUNT_OFFSET + 4;

    public static final int SNAP_LOCATIONCOUNT_OFFSET = SNAP_PLAYERS_OFFSET
                                                      + PLAYER_CAP * PLAYER_ENTRY_SIZE;
    public static final int SNAP_LOCATIONS_OFFSET     = SNAP_LOCATIONCOUNT_OFFSET + 4;

    // 4-byte explicit pad after the locations array keeps the following
    // ProducerState (alignof 8) 8-aligned. Mirrors Snapshot::_padAfterLocations
    // in SharedLayout.h; not accessed from Java but must be reserved here so
    // SNAP_INVENTORYCOUNT_OFFSET matches the C++ side.
    public static final int SNAP_INVENTORYCOUNT_OFFSET = SNAP_LOCATIONS_OFFSET
                                                       + LOCATION_CAP * LOCATION_ENTRY_SIZE
                                                       + 4;
    public static final int SNAP_INVENTORIES_OFFSET    = SNAP_INVENTORYCOUNT_OFFSET + 4;

    public static final int SNAP_INVITEMCOUNT_OFFSET = SNAP_INVENTORIES_OFFSET
                                                     + INVENTORY_CAP * INV_HEADER_SIZE;
    public static final int SNAP_INVITEMS_OFFSET     = SNAP_INVITEMCOUNT_OFFSET + 4;

    /** Producer-state tail block; layout matches SharedLayout.h::ProducerState.
     *  Directly follows the inventory-items array — the v13 wire dropped the
     *  {@code ifaceVersions[]} array that used to sit between them. */
    public static final int SNAP_PRODUCER_OFFSET = SNAP_INVITEMS_OFFSET
                                                 + INVENTORY_ITEM_CAP * INV_ITEM_SIZE;

    public static final int PRODUCER_SIZE                       = 32;
    public static final int PRODUCER_ACTIONQUEUESIZE_OFFSET     = 0;    // u32
    public static final int PRODUCER_ACTIONSBLOCKED_OFFSET      = 4;    // u8
    public static final int PRODUCER_ONBREAK_OFFSET             = 5;    // u8
    public static final int PRODUCER_LASTACTIONTIMEMS_OFFSET    = 8;    // u64
    public static final int PRODUCER_BREAKUNTILMS_OFFSET        = 16;   // u64
    /** Bumps when the producer's loaded_map_squares vector identity changes
     *  — a coarse "scene was streamed" signal consumers can use to invalidate
     *  per-region caches. Mirrors ProducerState::sceneVersion. */
    public static final int PRODUCER_SCENEVERSION_OFFSET        = 24;   // u32

    // ------------------------------------------------------------------
    // Open sub-interfaces tail (v14+)
    //
    // Per-tick snapshot of jag::InterfaceManager's open-subs hashmap. The
    // count fits in a u32; entries [0, count) carry the interface ids the
    // producer found this tick. {@link #isInterfaceOpen} on SnapshotView
    // does a linear scan — the keyset is small (~20 ids) and locality wins
    // over any data structure with constant overhead.
    // ------------------------------------------------------------------

    public static final int SNAP_OPENIFACECOUNT_OFFSET = SNAP_PRODUCER_OFFSET + PRODUCER_SIZE;
    public static final int SNAP_OPENIFACES_OFFSET     = SNAP_OPENIFACECOUNT_OFFSET + 4;

    // ------------------------------------------------------------------
    // Ground items tail (v15+)
    //
    // Per-tick snapshot of every alive ground-item stack within the
    // loaded-scene tile bounds, captured from the producer's ObjStackList
    // walk. Membership in this array is the canonical "what's on the
    // ground" signal — host facades scan it locally instead of paying a
    // per-tick RPC round-trip. Replaces the retired query_ground_items.
    // ------------------------------------------------------------------

    public static final int SNAP_GROUNDITEMCOUNT_OFFSET = SNAP_OPENIFACES_OFFSET
                                                        + OPEN_IFACE_CAP * 4;
    public static final int SNAP_GROUNDITEMS_OFFSET     = SNAP_GROUNDITEMCOUNT_OFFSET + 4;

    // ------------------------------------------------------------------
    // Projectiles tail (v17+)
    //
    // Per-tick snapshot of every in-flight projectile, walked from the
    // producer's projectile list. Membership in this array is the canonical
    // "what's flying right now" signal — host facades scan it locally instead
    // of paying a per-tick RPC round-trip.
    // ------------------------------------------------------------------

    public static final int SNAP_PROJECTILECOUNT_OFFSET = SNAP_GROUNDITEMS_OFFSET
                                                        + GROUND_ITEM_CAP * GROUND_ITEM_ENTRY_SIZE;
    public static final int SNAP_PROJECTILES_OFFSET     = SNAP_PROJECTILECOUNT_OFFSET + 4;

    /** The client's own game-cycle counter (i32, ~20ms) — the number the projectiles block
     *  above is stamped in, so diff {@code startCycle}/{@code endCycle} against this for flight
     *  progress. Reads {@code 0} only until the client populates its transmission manager —
     *  it is already counting in the lobby, so {@code 0} does not mean "not in a world"
     *  ({@link #SNAP_SERVERTICK_OFFSET} {@code == -1} is that signal). Distinct from
     *  {@link #SNAP_PUBLISHSEQ_OFFSET}, which
     *  shares the cadence but not the number space. Occupies the 4-byte tail slot that was the
     *  anonymous {@code _padAfterProjectiles} through v17: groundItems ended at 0 mod 8 and
     *  projectileCount(4) + projectiles[256]*32 lands at 4 mod 8, so the slot is needed to
     *  restore Snapshot's alignof-8 size either way — v18 just named it. */
    public static final int SNAP_GAMECYCLE_OFFSET = SNAP_PROJECTILES_OFFSET
                                                  + PROJECTILE_CAP * PROJECTILE_ENTRY_SIZE;

    // ------------------------------------------------------------------
    // DynamicRegion (36 bytes, alignof 4) — mirrors ipc::DynamicRegion in
    // SharedLayout.h. The header of the v19 dynamic-region tail block: the
    // scalars describing the client's instance chunk-descriptor grid, read
    // once per tick. The grid itself follows as dynChunkCount + dynChunks[].
    //
    // UNITS TRAP: originMapX/originMapY/maxMapX/maxMapY are MAPSQUARES
    // (64 tiles each); gridW/gridH are CHUNKS (8 tiles each). One mapsquare
    // is 8 chunks, so an origin only becomes a grid index after a x8. That
    // conversion happens exactly once, in DynamicRegion's resolver — do not
    // repeat it here and do not skip it there.
    //
    // gridW/gridH stay populated when truncated is set, so a consumer can
    // log "40x40 grid, cap 64x64" instead of seeing zeros. requiredChunks
    // (4*gridW*gridH) is always written, which makes an overflow diagnosable
    // rather than merely flagged.
    // ------------------------------------------------------------------

    public static final int DYNREGION_SIZE = 36;

    /** {@code 1} when the scene is a dynamic region (the client's descriptor pointer
     *  was non-null); {@code 0} for an ordinary static scene. */
    public static final int DYNREGION_ISINSTANCE_OFFSET     = 0;    // u8
    /** {@code 1} when the grid exceeded {@link #DYN_CHUNK_CAP}; the chunk array is
     *  then empty while gridW/gridH/requiredChunks stay populated. */
    public static final int DYNREGION_TRUNCATED_OFFSET      = 1;    // u8
    // bytes 2..3 are _pad0; not accessed
    /** {@code 3} = static, {@code 4..7} = dynamic size classes. */
    public static final int DYNREGION_SCENEMODE_OFFSET      = 4;    // i32
    public static final int DYNREGION_ORIGINMAPX_OFFSET     = 8;    // i32  min loaded MAPSQUARE X
    public static final int DYNREGION_ORIGINMAPY_OFFSET     = 12;   // i32
    public static final int DYNREGION_MAXMAPX_OFFSET        = 16;   // i32
    public static final int DYNREGION_MAXMAPY_OFFSET        = 20;   // i32
    public static final int DYNREGION_GRIDW_OFFSET          = 24;   // i32  width in CHUNKS
    public static final int DYNREGION_GRIDH_OFFSET          = 28;   // i32  height in CHUNKS
    public static final int DYNREGION_REQUIREDCHUNKS_OFFSET = 32;   // i32  4*gridW*gridH

    // ------------------------------------------------------------------
    // Dynamic region tail (v19+)
    //
    // dynChunks is plane-major: the descriptor for grid cell (gx, gy) on
    // plane p lives at index ((p * gridW) + gx) * gridH + gy. Each entry is
    // a raw packed u32 copied verbatim from the client; the BIT layout is
    // NOT here — it lives on api's DynamicRegion, because scripts decode it
    // and api cannot depend on core. One home, not two copies of a wire
    // contract.
    //
    // PRODUCER CONTRACT: in a static scene the producer publishes
    // dynChunkCount = 0 and leaves the dynChunks bytes stale/untouched — it
    // deliberately does not memset 64 KB per tick for nothing. Never read
    // past the count.
    // ------------------------------------------------------------------

    public static final int SNAP_DYNREGION_OFFSET     = SNAP_GAMECYCLE_OFFSET + 4;
    public static final int SNAP_DYNCHUNKCOUNT_OFFSET = SNAP_DYNREGION_OFFSET + DYNREGION_SIZE;
    public static final int SNAP_DYNCHUNKS_OFFSET     = SNAP_DYNCHUNKCOUNT_OFFSET + 4;

    public static final int SNAPSHOT_SIZE = SNAP_DYNCHUNKS_OFFSET + DYN_CHUNK_CAP * 4;

    // ------------------------------------------------------------------
    // Event ring
    //
    // EventSlot {
    //   u64 seq;
    //   u32 type;
    //   u32 bodyLen;
    //   u8  body[112];
    // }  // 128 bytes
    //
    // EventRing {
    //   u64 head;
    //   u32 slotCount;
    //   u32 slotMask;
    //   u32 droppedCount;
    //   u32 _reserved[3];
    //   EventSlot slots[1024];
    // }
    // ------------------------------------------------------------------

    public static final int EVENT_RING_SLOTS    = 1024;
    public static final int EVENT_BODY_MAX      = 112;
    public static final int EVENT_SLOT_SIZE     = 128;

    public static final int RING_HEAD_OFFSET         = 0;
    public static final int RING_SLOTCOUNT_OFFSET    = 8;
    public static final int RING_SLOTMASK_OFFSET     = 12;
    public static final int RING_DROPPEDCOUNT_OFFSET = 16;
    public static final int RING_SLOTS_OFFSET        = 32;

    public static final int SLOT_SEQ_OFFSET     = 0;
    public static final int SLOT_TYPE_OFFSET    = 8;
    public static final int SLOT_BODYLEN_OFFSET = 12;
    public static final int SLOT_BODY_OFFSET    = 16;

    // ------------------------------------------------------------------
    // EventType discriminator (Events.h ipc::EventType)
    // ------------------------------------------------------------------

    public static final int EVT_NONE                = 0;
    public static final int EVT_LOGIN_STATE_CHANGE  = 1;
    public static final int EVT_TICK                = 2;
    public static final int EVT_VAR_CHANGE          = 10;
    public static final int EVT_VARBIT_CHANGE       = 11;
    public static final int EVT_VARC_CHANGE         = 12;
    public static final int EVT_OBJ_VAR_CHANGE      = 13;
    public static final int EVT_CHAT_MESSAGE        = 20;
    public static final int EVT_KEY_INPUT           = 30;
    public static final int EVT_ACTION_EXECUTED     = 40;
    public static final int EVT_BREAK_STARTED       = 50;
    public static final int EVT_BREAK_ENDED         = 51;
    public static final int EVT_WALK_ARRIVED        = 60;
    public static final int EVT_WALK_CANCELLED      = 61;
    public static final int EVT_WALK_FAILED         = 62;
    public static final int EVT_HITMARK             = 70;
    public static final int EVT_HEADBAR             = 71;
    public static final int EVT_SPOT_ANIM           = 72;
    public static final int EVT_RADIO_GROUP_SELECT  = 80;
}
