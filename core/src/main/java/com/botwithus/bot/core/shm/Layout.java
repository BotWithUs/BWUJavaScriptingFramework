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
     *  v14 added the {@code openIfaces[]} tail block — a per-tick snapshot of every entry in
     *  jag::InterfaceManager's open-subs hashmap, so {@code isInterfaceOpen}-style checks no
     *  longer pay a per-call RPC round-trip.
     *  v13 dropped the per-interface {@code ifaceVersions[]} array; interface state is read
     *  fresh on demand via RPC rather than cached behind an invalidation token. */
    public static final int PROTOCOL_VERSION = 14;

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
    // NpcEntry  (32 bytes)
    // ------------------------------------------------------------------

    public static final int NPC_ENTRY_SIZE = 32;

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
    // PlayerEntry (24 bytes)
    // ------------------------------------------------------------------

    public static final int PLAYER_ENTRY_SIZE = 24;

    public static final int PLAYER_SERVERINDEX_OFFSET    = 0;    // i32
    public static final int PLAYER_TILEX_OFFSET          = 4;    // i16
    public static final int PLAYER_TILEY_OFFSET          = 6;    // i16
    public static final int PLAYER_PLANE_OFFSET          = 8;    // i8
    public static final int PLAYER_FLAGS_OFFSET          = 9;    // u8
    public static final int PLAYER_FOLLOWINGINDEX_OFFSET = 10;   // i16
    public static final int PLAYER_ANIMATIONID_OFFSET    = 12;   // i32
    public static final int PLAYER_STANCEID_OFFSET       = 16;   // i32
    public static final int PLAYER_COMBATLEVEL_OFFSET    = 20;   // i32

    // ------------------------------------------------------------------
    // SkillEntry (16 bytes)
    // ------------------------------------------------------------------

    public static final int SKILL_ENTRY_SIZE = 16;

    public static final int SKILL_TYPEID_OFFSET       = 0;
    public static final int SKILL_EXPERIENCE_OFFSET   = 4;
    public static final int SKILL_ACTUALLEVEL_OFFSET  = 8;
    public static final int SKILL_BOOSTEDLEVEL_OFFSET = 12;

    // ------------------------------------------------------------------
    // LocalPlayer (32 + 32*16 = 544 bytes)
    // ------------------------------------------------------------------

    public static final int LOCAL_PLAYER_SIZE = 544;

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
    public static final int LP_SKILLCOUNT_OFFSET      = 28;
    public static final int LP_SKILLS_OFFSET          = 32;

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

    public static final int SNAP_TICKID_OFFSET       = 0;     // u64
    public static final int SNAP_GAMESTATE_OFFSET    = 8;     // i32
    public static final int SNAP_OWNINDEX_OFFSET     = 12;    // i32
    /** Active root interface id (e.g. 1477 in resizable HUD mode); -1 when no root mounted. */
    public static final int SNAP_ROOTIFACEID_OFFSET  = 16;    // i32
    // Slot at +20 is _reserved0 (i32) — pad to keep the producer block 8-aligned;
    // not accessed from Java but the offset must be reserved here so SNAP_SELF_OFFSET
    // matches the C++ side. See SharedLayout.h Snapshot::_reserved0 for rationale.
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

    // 4-byte explicit trailing pad: openIfaceCount(4) + openIfaces[64](256) =
    // 260, which lands at 4 mod 8. ProducerState aligns Snapshot to 8 so the
    // C++ compiler inserts implicit trailing padding; mirroring it here keeps
    // SNAPSHOT_SIZE = sizeof(Snapshot). See SharedLayout.h Snapshot::_padTail.
    public static final int SNAPSHOT_SIZE = SNAP_OPENIFACES_OFFSET
                                          + OPEN_IFACE_CAP * 4
                                          + 4;

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
}
