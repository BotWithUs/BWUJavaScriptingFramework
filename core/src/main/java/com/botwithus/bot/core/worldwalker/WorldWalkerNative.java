package com.botwithus.bot.core.worldwalker;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Raw Panama downcall handles for the WorldWalker C ABI plus the
 * {@link FunctionDescriptor}s used to install the executor's upcall vtable.
 *
 * <p>Package-private — callers use {@link WorldWalker} instead.</p>
 *
 * <p>Field names use Java {@code camelCase} ({@code wwArtifactOpen}); native
 * symbol names ({@code ww_artifact_open}) are passed verbatim to
 * {@link SymbolLookup#find(String)} and must stay in lock-step with the C
 * header.</p>
 */
final class WorldWalkerNative {

    // ── Error + memory ─────────────────────────────────────────────────────

    final MethodHandle wwLastError;          // () -> ptr
    final MethodHandle wwFree;               // (ptr) -> void

    // ── Artifact lifecycle ─────────────────────────────────────────────────

    final MethodHandle wwArtifactOpen;       // (ptr) -> ptr
    final MethodHandle wwArtifactClose;      // (ptr) -> void
    final MethodHandle wwArtifactLoadTeleports; // (ptr, ptr) -> int

    // ── Context pool lifecycle ─────────────────────────────────────────────

    final MethodHandle wwContextPoolCreate;  // (ptr, usize) -> ptr
    final MethodHandle wwContextPoolDestroy; // (ptr) -> void

    // ── Query ──────────────────────────────────────────────────────────────

    final MethodHandle wwQuery;              // (ptr, ptr, WwTile, WwGoal, ptr, ptr) -> int
    final MethodHandle wwQueryEx;            // (ptr, ptr, WwTile, WwGoal, ptr, ptr, ptr) -> int
    final MethodHandle wwPathFree;           // (ptr) -> void

    // ── Executor ───────────────────────────────────────────────────────────

    final MethodHandle wwExecutorRun;        // (ptr, ptr, WwGoal, ptr) -> int

    // ── Upcall descriptors (used by UpcallStubs.install) ───────────────────

    /** {@code void(*)(void *user, WwTile *outTile)}. */
    static final FunctionDescriptor FD_READ_POSITION =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /** {@code void(*)(void *user, WwCapabilitySnapshot *outSnapshot)}. */
    static final FunctionDescriptor FD_READ_CAPABILITY =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /** {@code void(*)(void *user, WwInstanceChunks *outChunks)}. */
    static final FunctionDescriptor FD_READ_INSTANCE =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /** {@code int32_t(*)(void *user, int32_t itemId)}. */
    static final FunctionDescriptor FD_READ_ITEM_COUNT =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    /** {@code void(*)(void *user, const int32_t *ids, size_t count, int32_t *outValues)}. */
    static final FunctionDescriptor FD_READ_VARBITS =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);

    /** {@code void(*)(void *user, const int32_t *ids, size_t count, int32_t *outValues)}. */
    static final FunctionDescriptor FD_READ_ITEM_COUNTS =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);

    /** {@code int32_t(*)(void *user, int32_t itemId)} — non-zero if worn. */
    static final FunctionDescriptor FD_IS_ITEM_WORN =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    /** {@code int32_t(*)(void *user, int32_t interfaceId)} — non-zero when
        the interface is mounted in the engine's open-subs hashmap. The
        bridge implementation delegates to {@code GameSnapshot.isInterfaceOpen},
        which is a sub-microsecond SHM linear scan (no RPC). */
    static final FunctionDescriptor FD_IS_INTERFACE_OPEN =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

    /** {@code void(*)(void *user, WwTile target)} — target passed by value. */
    static final FunctionDescriptor FD_WALK_TO =
            FunctionDescriptor.ofVoid(ADDRESS, WorldWalkerLayouts.WW_TILE);

    /** {@code int32_t(*)(void *user, int32_t objectId, WwTile tile, int32_t optionIndex)} —
        returns 1 if an action was issued, 0 if a no-op (loc absent / door already open). */
    static final FunctionDescriptor FD_INTERACT = FunctionDescriptor.of(
            JAVA_INT, ADDRESS, JAVA_INT, WorldWalkerLayouts.WW_TILE, JAVA_INT);

    /** {@code void(*)(void *user, int32_t kind, int32_t a..i)} — kind + nine generic slots. */
    static final FunctionDescriptor FD_RUN_CHAIN_STEP =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

    /** {@code void(*)(void *user, int32_t ticks)}. */
    static final FunctionDescriptor FD_SLEEP_TICKS =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);

    /** {@code int32_t(*)(void *user)}. */
    static final FunctionDescriptor FD_SHOULD_CANCEL =
            FunctionDescriptor.of(JAVA_INT, ADDRESS);

    /** {@code void(*)(void *user, const WwEvent *event)}. */
    static final FunctionDescriptor FD_ON_EVENT =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    WorldWalkerNative(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();

        wwLastError = downcall(linker, lookup, "ww_last_error",
                FunctionDescriptor.of(ADDRESS));
        wwFree = downcall(linker, lookup, "ww_free",
                FunctionDescriptor.ofVoid(ADDRESS));

        wwArtifactOpen = downcall(linker, lookup, "ww_artifact_open",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        wwArtifactClose = downcall(linker, lookup, "ww_artifact_close",
                FunctionDescriptor.ofVoid(ADDRESS));
        wwArtifactLoadTeleports = downcall(linker, lookup, "ww_artifact_load_teleports",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

        wwContextPoolCreate = downcall(linker, lookup, "ww_context_pool_create",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        wwContextPoolDestroy = downcall(linker, lookup, "ww_context_pool_destroy",
                FunctionDescriptor.ofVoid(ADDRESS));

        // WwTile (12B) and WwGoal (16B) pass by value at the ABI boundary; the
        // StructLayouts on the FunctionDescriptor instruct Panama to emit the
        // right struct-passing convention (regs/stack) for the platform.
        wwQuery = downcall(linker, lookup, "ww_query",
                FunctionDescriptor.of(JAVA_INT,
                        ADDRESS,                                // ww_artifact*
                        ADDRESS,                                // ww_context_pool*
                        WorldWalkerLayouts.WW_TILE,             // WwTile (by value)
                        WorldWalkerLayouts.WW_GOAL,             // WwGoal (by value)
                        ADDRESS,                                // const WwCapabilitySnapshot*
                        ADDRESS));                              // WwPath* (out)
        // Same call with the scene's dynamic-region grid attached, so a query
        // issued inside a player-owned house or a Dungeoneering floor resolves
        // collision through the chunk descriptors instead of reading the whole
        // instance as unmapped (and therefore solid).
        wwQueryEx = downcall(linker, lookup, "ww_query_ex",
                FunctionDescriptor.of(JAVA_INT,
                        ADDRESS,                                // ww_artifact*
                        ADDRESS,                                // ww_context_pool*
                        WorldWalkerLayouts.WW_TILE,             // WwTile (by value)
                        WorldWalkerLayouts.WW_GOAL,             // WwGoal (by value)
                        ADDRESS,                                // const WwCapabilitySnapshot*
                        ADDRESS,                                // const WwInstanceChunks*
                        ADDRESS));                              // WwPath* (out)
        wwPathFree = downcall(linker, lookup, "ww_path_free",
                FunctionDescriptor.ofVoid(ADDRESS));

        wwExecutorRun = downcall(linker, lookup, "ww_executor_run",
                FunctionDescriptor.of(JAVA_INT,
                        ADDRESS,                                // ww_artifact*
                        ADDRESS,                                // ww_context_pool*
                        WorldWalkerLayouts.WW_GOAL,             // WwGoal (by value)
                        ADDRESS));                              // const WwCallbacks*
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
                                         String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("worldwalker.dll missing symbol: " + name));
        return linker.downcallHandle(symbol, fd);
    }
}
