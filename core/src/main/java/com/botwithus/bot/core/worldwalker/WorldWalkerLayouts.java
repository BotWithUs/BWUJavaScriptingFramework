package com.botwithus.bot.core.worldwalker;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

/**
 * Panama {@link StructLayout} definitions and shared sentinel constants for the
 * WorldWalker C ABI ({@code worldwalker.dll}). Wire shapes mirror
 * {@code WorldWalker/src/c_api/worldwalker_c.h} byte-for-byte; sizes are pinned
 * by {@code static_assert}s on the C++ side and re-validated below.
 *
 * <p>Callers obtain sized storage with {@code arena.allocate(LAYOUT)} and access
 * fields through the named offset constants on each layout's owner type
 * (e.g. {@link WwPath#STEPS_OFFSET}). The offsets are hand-written rather than
 * derived via {@code VarHandle} to stay grep-friendly with the C header.</p>
 *
 * <p>This class is package-private — public callers go through
 * {@link WorldWalker} or the typed records.</p>
 */
final class WorldWalkerLayouts {

    private WorldWalkerLayouts() {}

    // ── ww_result / WW_STATUS_* / WW_EVENT_* / WW_STEP_KIND_* sentinels ────

    static final int WW_OK            = 0;
    static final int WW_ERR_INVALID   = 1;
    static final int WW_ERR_NOT_FOUND = 2;
    static final int WW_ERR_VERSION   = 3;
    static final int WW_ERR_IO        = 4;
    static final int WW_ERR_INTERNAL  = 5;

    static final int WW_STEP_KIND_WALK       = 0;
    static final int WW_STEP_KIND_TRANSITION = 1;

    static final int WW_STATUS_ARRIVED   = 0;
    static final int WW_STATUS_FAILED    = 1;
    static final int WW_STATUS_CANCELLED = 2;

    static final int WW_EVENT_STEP_ADVANCED       = 0;
    static final int WW_EVENT_WALKING_TO_INTERACT = 1;
    static final int WW_EVENT_TELEPORT_INITIATED  = 2;
    static final int WW_EVENT_STUCK               = 3;
    static final int WW_EVENT_REPLAN_STARTED      = 4;
    static final int WW_EVENT_ARRIVED             = 5;
    static final int WW_EVENT_FAILED              = 6;

    // ── Struct layouts ─────────────────────────────────────────────────────

    /** {@code WwTile { i32 x, y, plane; }} — 12 bytes. */
    static final StructLayout WW_TILE = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("x"),
            ValueLayout.JAVA_INT.withName("y"),
            ValueLayout.JAVA_INT.withName("plane")
    );

    /** {@code WwGoal { i32 x, y, plane, radius; }} — 16 bytes. */
    static final StructLayout WW_GOAL = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("x"),
            ValueLayout.JAVA_INT.withName("y"),
            ValueLayout.JAVA_INT.withName("plane"),
            ValueLayout.JAVA_INT.withName("radius")
    );

    /** {@code WwStep { u8 kind; u8 plane; u16 pad; i32 targetX, targetY; u32 transitionIndex; }} — 16 bytes. */
    static final StructLayout WW_STEP = MemoryLayout.structLayout(
            ValueLayout.JAVA_BYTE.withName("kind"),
            ValueLayout.JAVA_BYTE.withName("plane"),
            ValueLayout.JAVA_SHORT.withName("pad"),
            ValueLayout.JAVA_INT.withName("targetX"),
            ValueLayout.JAVA_INT.withName("targetY"),
            ValueLayout.JAVA_INT.withName("transitionIndex")
    );

    /** {@code WwPath { WwStep* steps; usize stepCount; f32 cost; i32 pad; }} — 24 bytes on 64-bit. */
    static final StructLayout WW_PATH = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("steps"),
            ValueLayout.JAVA_LONG.withName("stepCount"),
            ValueLayout.JAVA_FLOAT.withName("cost"),
            ValueLayout.JAVA_INT.withName("pad")
    );

    /** {@code WwCapabilityEntry { i32 id; i32 value; }} — 8 bytes. */
    static final StructLayout WW_CAPABILITY_ENTRY = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("id"),
            ValueLayout.JAVA_INT.withName("value")
    );

    /** {@code WwCapabilitySnapshot} — four (ptr, count) runs, 64 bytes on 64-bit. */
    static final StructLayout WW_CAPABILITY_SNAPSHOT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("skills"),
            ValueLayout.JAVA_LONG.withName("skillCount"),
            ValueLayout.ADDRESS.withName("items"),
            ValueLayout.JAVA_LONG.withName("itemCount"),
            ValueLayout.ADDRESS.withName("varbits"),
            ValueLayout.JAVA_LONG.withName("varbitCount"),
            ValueLayout.ADDRESS.withName("varps"),
            ValueLayout.JAVA_LONG.withName("varpCount")
    );

    /** {@code WwEvent { i32 kind; i32 pad; i32 stepIndex; i32 transitionIndex; }} — 16 bytes. */
    static final StructLayout WW_EVENT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("kind"),
            ValueLayout.JAVA_INT.withName("pad"),
            ValueLayout.JAVA_INT.withName("stepIndex"),
            ValueLayout.JAVA_INT.withName("transitionIndex")
    );

    /**
     * {@code WwCallbacks} — opaque {@code user} cookie + fourteen function pointers
     * (fifteen 8-byte slots), 120 bytes on 64-bit. Field order is fixed by the C
     * struct in {@code worldwalker_c.h}: any reorder there must reorder both
     * this layout and the offset constants below.
     */
    static final StructLayout WW_CALLBACKS = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("user"),
            ValueLayout.ADDRESS.withName("readPosition"),
            ValueLayout.ADDRESS.withName("readCapability"),
            ValueLayout.ADDRESS.withName("readVarbit"),
            ValueLayout.ADDRESS.withName("readItemCount"),
            ValueLayout.ADDRESS.withName("readVarbits"),
            ValueLayout.ADDRESS.withName("readItemCounts"),
            ValueLayout.ADDRESS.withName("isItemWorn"),
            ValueLayout.ADDRESS.withName("isInterfaceOpen"),
            ValueLayout.ADDRESS.withName("walkTo"),
            ValueLayout.ADDRESS.withName("interact"),
            ValueLayout.ADDRESS.withName("runChainStep"),
            ValueLayout.ADDRESS.withName("sleepTicks"),
            ValueLayout.ADDRESS.withName("shouldCancel"),
            ValueLayout.ADDRESS.withName("onEvent")
    );

    // WwCallbacks slot offsets — one pointer wide each, packed at 8B stride.
    static final long CB_USER_OFFSET              =   0;
    static final long CB_READ_POSITION_OFFSET     =   8;
    static final long CB_READ_CAPABILITY_OFFSET   =  16;
    static final long CB_READ_VARBIT_OFFSET       =  24;
    static final long CB_READ_ITEM_COUNT_OFFSET   =  32;
    static final long CB_READ_VARBITS_OFFSET      =  40;
    static final long CB_READ_ITEM_COUNTS_OFFSET  =  48;
    static final long CB_IS_ITEM_WORN_OFFSET      =  56;
    static final long CB_IS_INTERFACE_OPEN_OFFSET =  64;
    static final long CB_WALK_TO_OFFSET           =  72;
    static final long CB_INTERACT_OFFSET          =  80;
    static final long CB_RUN_CHAIN_STEP_OFFSET    =  88;
    static final long CB_SLEEP_TICKS_OFFSET       =  96;
    static final long CB_SHOULD_CANCEL_OFFSET     = 104;
    static final long CB_ON_EVENT_OFFSET          = 112;

    // Pin layout sizes so a future structLayout typo trips the class loader
    // rather than misreading bytes at runtime.
    static {
        assertSize(WW_TILE,                12, "WwTile");
        assertSize(WW_GOAL,                16, "WwGoal");
        assertSize(WW_STEP,                16, "WwStep");
        assertSize(WW_PATH,                24, "WwPath");
        assertSize(WW_CAPABILITY_ENTRY,     8, "WwCapabilityEntry");
        assertSize(WW_CAPABILITY_SNAPSHOT, 64, "WwCapabilitySnapshot");
        assertSize(WW_EVENT,               16, "WwEvent");
        assertSize(WW_CALLBACKS,          120, "WwCallbacks");
    }

    private static void assertSize(MemoryLayout layout, long expected, String name) {
        if (layout.byteSize() != expected) {
            throw new AssertionError(
                    name + " layout size " + layout.byteSize() + " != expected " + expected);
        }
    }
}
