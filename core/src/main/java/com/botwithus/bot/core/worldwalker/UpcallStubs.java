package com.botwithus.bot.core.worldwalker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Wires {@link WwCallbacks} to ten C-ABI upcall stubs that match the
 * {@code WwCallbacks} vtable in {@code worldwalker_c.h}.
 *
 * <p>Each stub catches every {@link Throwable} the callback raises and stores
 * the first one into the per-run error sink — Java exceptions never escape a
 * native frame. {@code shouldCancel} short-circuits to {@code 1} once the sink
 * is non-null, so the executor aborts at its next safe point; the original
 * throwable is then rethrown on the calling thread by
 * {@link WorldWalker#runExecutor}.</p>
 *
 * <p>All upcall stubs and the {@code WwCallbacks} struct itself live in a
 * single {@link Arena} owned by {@code runExecutor} for the duration of the
 * call. {@code WwCapabilityEntry} arrays produced by repeated
 * {@code readCapability} invocations are allocated into the same arena and
 * accumulate there until {@code runExecutor} returns — bounded in practice
 * (≤ ~400 B per snapshot × a handful of replans) and well below worth the
 * complexity of a recycling arena.</p>
 */
final class UpcallStubs {

    private static final Logger log = LoggerFactory.getLogger(UpcallStubs.class);
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final MethodHandle MH_READ_POSITION;
    private static final MethodHandle MH_READ_CAPABILITY;
    private static final MethodHandle MH_READ_VARBIT;
    private static final MethodHandle MH_IS_INTERFACE_OPEN;
    private static final MethodHandle MH_WALK_TO;
    private static final MethodHandle MH_INTERACT;
    private static final MethodHandle MH_RUN_CHAIN_STEP;
    private static final MethodHandle MH_SLEEP_TICKS;
    private static final MethodHandle MH_SHOULD_CANCEL;
    private static final MethodHandle MH_ON_EVENT;

    static {
        try {
            MH_READ_POSITION = LOOKUP.findStatic(UpcallStubs.class, "readPositionImpl",
                    MethodType.methodType(void.class, Run.class, MemorySegment.class, MemorySegment.class));
            MH_READ_CAPABILITY = LOOKUP.findStatic(UpcallStubs.class, "readCapabilityImpl",
                    MethodType.methodType(void.class, Run.class, MemorySegment.class, MemorySegment.class));
            MH_READ_VARBIT = LOOKUP.findStatic(UpcallStubs.class, "readVarbitImpl",
                    MethodType.methodType(int.class, Run.class, MemorySegment.class, int.class));
            MH_IS_INTERFACE_OPEN = LOOKUP.findStatic(UpcallStubs.class, "isInterfaceOpenImpl",
                    MethodType.methodType(int.class, Run.class, MemorySegment.class, int.class));
            MH_WALK_TO = LOOKUP.findStatic(UpcallStubs.class, "walkToImpl",
                    MethodType.methodType(void.class, Run.class, MemorySegment.class, MemorySegment.class));
            MH_INTERACT = LOOKUP.findStatic(UpcallStubs.class, "interactImpl",
                    MethodType.methodType(int.class, Run.class, MemorySegment.class, int.class, MemorySegment.class, int.class));
            MH_RUN_CHAIN_STEP = LOOKUP.findStatic(UpcallStubs.class, "runChainStepImpl",
                    MethodType.methodType(void.class, Run.class, MemorySegment.class, int.class, int.class));
            MH_SLEEP_TICKS = LOOKUP.findStatic(UpcallStubs.class, "sleepTicksImpl",
                    MethodType.methodType(void.class, Run.class, MemorySegment.class, int.class));
            MH_SHOULD_CANCEL = LOOKUP.findStatic(UpcallStubs.class, "shouldCancelImpl",
                    MethodType.methodType(int.class, Run.class, MemorySegment.class));
            MH_ON_EVENT = LOOKUP.findStatic(UpcallStubs.class, "onEventImpl",
                    MethodType.methodType(void.class, Run.class, MemorySegment.class, MemorySegment.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // findStatic failures here are structural — a refactor renamed an
            // impl method but didn't update the registry. Fail loud at class
            // load rather than at the first upcall.
            throw new ExceptionInInitializerError(e);
        }
    }

    private UpcallStubs() {}

    /**
     * Per-{@code runExecutor} state shared across the ten upcall stubs.
     * Holds the callback instance, the run arena (used for
     * {@code WwCapabilityEntry} arrays), and the first-write-wins error sink.
     */
    static final class Run {

        final WwCallbacks callbacks;
        final Arena arena;
        /** Pointer to the populated {@code WwCallbacks} struct in {@link #arena}. */
        MemorySegment callbacksStruct;
        private volatile Throwable error;

        Run(WwCallbacks callbacks, Arena arena) {
            this.callbacks = callbacks;
            this.arena = arena;
        }

        /** First-write wins so we surface the original cause, not a follower. */
        synchronized void recordError(Throwable t) {
            if (error == null) {
                error = t;
            }
            log.debug("WorldWalker callback threw", t);
        }

        Throwable error() {
            return error;
        }

        boolean hasError() {
            return error != null;
        }
    }

    /**
     * Allocate a {@code WwCallbacks} struct into {@code arena}, populate its
     * ten slots with upcall stubs that delegate to {@code callbacks}, and
     * return the wrapping {@link Run}.
     *
     * @param linker     {@link Linker#nativeLinker()}
     * @param arena      arena owning the stubs + the struct + run-time
     *                   capability entry allocations
     * @param callbacks  the host callback surface
     */
    static Run install(Linker linker, Arena arena, WwCallbacks callbacks) {
        Run run = new Run(callbacks, arena);
        MemorySegment struct = arena.allocate(WorldWalkerLayouts.WW_CALLBACKS);

        struct.set(ADDRESS, WorldWalkerLayouts.CB_USER_OFFSET, MemorySegment.NULL);
        struct.set(ADDRESS, WorldWalkerLayouts.CB_READ_POSITION_OFFSET,
                stub(linker, arena, MH_READ_POSITION, run, WorldWalkerNative.FD_READ_POSITION));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_READ_CAPABILITY_OFFSET,
                stub(linker, arena, MH_READ_CAPABILITY, run, WorldWalkerNative.FD_READ_CAPABILITY));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_READ_VARBIT_OFFSET,
                stub(linker, arena, MH_READ_VARBIT, run, WorldWalkerNative.FD_READ_VARBIT));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_IS_INTERFACE_OPEN_OFFSET,
                stub(linker, arena, MH_IS_INTERFACE_OPEN, run, WorldWalkerNative.FD_IS_INTERFACE_OPEN));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_WALK_TO_OFFSET,
                stub(linker, arena, MH_WALK_TO, run, WorldWalkerNative.FD_WALK_TO));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_INTERACT_OFFSET,
                stub(linker, arena, MH_INTERACT, run, WorldWalkerNative.FD_INTERACT));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_RUN_CHAIN_STEP_OFFSET,
                stub(linker, arena, MH_RUN_CHAIN_STEP, run, WorldWalkerNative.FD_RUN_CHAIN_STEP));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_SLEEP_TICKS_OFFSET,
                stub(linker, arena, MH_SLEEP_TICKS, run, WorldWalkerNative.FD_SLEEP_TICKS));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_SHOULD_CANCEL_OFFSET,
                stub(linker, arena, MH_SHOULD_CANCEL, run, WorldWalkerNative.FD_SHOULD_CANCEL));
        struct.set(ADDRESS, WorldWalkerLayouts.CB_ON_EVENT_OFFSET,
                stub(linker, arena, MH_ON_EVENT, run, WorldWalkerNative.FD_ON_EVENT));

        run.callbacksStruct = struct;
        return run;
    }

    private static MemorySegment stub(Linker linker, Arena arena, MethodHandle impl,
                                      Run run, java.lang.foreign.FunctionDescriptor fd) {
        return linker.upcallStub(impl.bindTo(run), fd, arena);
    }

    // ── Upcall implementations ─────────────────────────────────────────────
    // Each catches Throwable to keep Java exceptions out of native frames.
    // The bound `run` carries shared state; the `user` cookie is always NULL
    // because the Java side binds context through method-handle binding
    // instead of through the C cookie slot.

    static void readPositionImpl(Run run, MemorySegment user, MemorySegment outTile) {
        try {
            WwTile t = run.callbacks.readPosition();
            MemorySegment view = outTile.reinterpret(WorldWalkerLayouts.WW_TILE.byteSize());
            view.set(JAVA_INT, 0, t.x());
            view.set(JAVA_INT, 4, t.y());
            view.set(JAVA_INT, 8, t.plane());
        } catch (Throwable thrown) {
            run.recordError(thrown);
        }
    }

    static void readCapabilityImpl(Run run, MemorySegment user, MemorySegment outSnapshot) {
        try {
            MemorySegment view = outSnapshot.reinterpret(WorldWalkerLayouts.WW_CAPABILITY_SNAPSHOT.byteSize());
            CapabilitySnapshot snap = run.callbacks.readCapability();
            if (snap == null || snap.isEmpty()) {
                zeroRun(view,  0);
                zeroRun(view, 16);
                zeroRun(view, 32);
                zeroRun(view, 48);
                return;
            }
            writeRun(run.arena, view,  0, snap.skills());
            writeRun(run.arena, view, 16, snap.items());
            writeRun(run.arena, view, 32, snap.varbits());
            writeRun(run.arena, view, 48, snap.varps());
        } catch (Throwable thrown) {
            run.recordError(thrown);
        }
    }

    static int readVarbitImpl(Run run, MemorySegment user, int id) {
        try {
            return run.callbacks.readVarbit(id);
        } catch (Throwable thrown) {
            run.recordError(thrown);
            return 0;
        }
    }

    static int isInterfaceOpenImpl(Run run, MemorySegment user, int interfaceId) {
        try {
            return run.callbacks.isInterfaceOpen(interfaceId) ? 1 : 0;
        } catch (Throwable thrown) {
            run.recordError(thrown);
            return 0;
        }
    }

    static void walkToImpl(Run run, MemorySegment user, MemorySegment targetSeg) {
        try {
            run.callbacks.walkTo(readTile(targetSeg));
        } catch (Throwable thrown) {
            run.recordError(thrown);
        }
    }

    static int interactImpl(Run run, MemorySegment user, int objectId, MemorySegment tileSeg, int optionIndex) {
        try {
            return run.callbacks.interact(objectId, readTile(tileSeg), optionIndex);
        } catch (Throwable thrown) {
            run.recordError(thrown);
            return 0;
        }
    }

    static void runChainStepImpl(Run run, MemorySegment user, int chainIndex, int stepIndex) {
        try {
            run.callbacks.runChainStep(chainIndex, stepIndex);
        } catch (Throwable thrown) {
            run.recordError(thrown);
        }
    }

    static void sleepTicksImpl(Run run, MemorySegment user, int ticks) {
        try {
            run.callbacks.sleepTicks(ticks);
        } catch (Throwable thrown) {
            run.recordError(thrown);
        }
    }

    static int shouldCancelImpl(Run run, MemorySegment user) {
        // Once a callback has thrown, force-cancel so the executor stops
        // calling us. The original throwable is surfaced by runExecutor at exit.
        if (run.hasError()) {
            return 1;
        }
        try {
            return run.callbacks.shouldCancel() ? 1 : 0;
        } catch (Throwable thrown) {
            run.recordError(thrown);
            return 1;
        }
    }

    static void onEventImpl(Run run, MemorySegment user, MemorySegment eventPtr) {
        try {
            MemorySegment view = eventPtr.reinterpret(WorldWalkerLayouts.WW_EVENT.byteSize());
            int rawKind         = view.get(JAVA_INT, 0);
            int stepIndex       = view.get(JAVA_INT, 8);
            int transitionIndex = view.get(JAVA_INT, 12);
            WwEvent ev = new WwEvent(
                    WwEventKind.fromWire(rawKind), rawKind, stepIndex, transitionIndex);
            run.callbacks.onEvent(ev);
        } catch (Throwable thrown) {
            run.recordError(thrown);
        }
    }

    // ── Local helpers ──────────────────────────────────────────────────────

    private static WwTile readTile(MemorySegment seg) {
        // By-value structs at upcall boundaries arrive with the declared
        // layout size, so reinterpret isn't required for direct field reads.
        return new WwTile(
                seg.get(JAVA_INT, 0),
                seg.get(JAVA_INT, 4),
                seg.get(JAVA_INT, 8));
    }

    private static void zeroRun(MemorySegment snapshot, long runOffset) {
        snapshot.set(ADDRESS,   runOffset,     MemorySegment.NULL);
        snapshot.set(JAVA_LONG, runOffset + 8, 0L);
    }

    private static void writeRun(Arena arena, MemorySegment snapshot, long runOffset,
                                 Map<Integer, Integer> entries) {
        if (entries.isEmpty()) {
            zeroRun(snapshot, runOffset);
            return;
        }
        int count = entries.size();
        MemorySegment buf = arena.allocate(
                WorldWalkerLayouts.WW_CAPABILITY_ENTRY.byteSize() * count,
                WorldWalkerLayouts.WW_CAPABILITY_ENTRY.byteAlignment());
        int i = 0;
        for (Map.Entry<Integer, Integer> e : entries.entrySet()) {
            long base = i * WorldWalkerLayouts.WW_CAPABILITY_ENTRY.byteSize();
            buf.set(JAVA_INT, base,     e.getKey());
            buf.set(JAVA_INT, base + 4, e.getValue());
            i++;
        }
        snapshot.set(ADDRESS,   runOffset,     buf);
        snapshot.set(JAVA_LONG, runOffset + 8, count);
    }
}
