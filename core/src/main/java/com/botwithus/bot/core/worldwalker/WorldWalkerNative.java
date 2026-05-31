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
 * Raw Panama downcall handles for the query surface of the WorldWalker C ABI.
 *
 * <p>Package-private — callers use {@link WorldWalker} instead. The executor
 * entry ({@code ww_executor_run}) and its callback vtable land in a follow-up
 * commit; only the query / lifecycle surface is bound here.</p>
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

    // ── Context pool lifecycle ─────────────────────────────────────────────

    final MethodHandle wwContextPoolCreate;  // (ptr, usize) -> ptr
    final MethodHandle wwContextPoolDestroy; // (ptr) -> void

    // ── Query ──────────────────────────────────────────────────────────────

    final MethodHandle wwQuery;              // (ptr, ptr, WwTile, WwGoal, ptr, ptr) -> int
    final MethodHandle wwPathFree;           // (ptr) -> void

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
        wwPathFree = downcall(linker, lookup, "ww_path_free",
                FunctionDescriptor.ofVoid(ADDRESS));
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup,
                                         String name, FunctionDescriptor fd) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("worldwalker.dll missing symbol: " + name));
        return linker.downcallHandle(symbol, fd);
    }
}
