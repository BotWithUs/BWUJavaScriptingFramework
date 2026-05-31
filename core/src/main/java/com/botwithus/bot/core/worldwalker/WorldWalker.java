package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.core.loader.NativeCache;
import com.botwithus.bot.core.util.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Java wrapper around the WorldWalker query surface ({@code worldwalker.dll}).
 *
 * <p>Thin Panama/FFM binding over {@code worldwalker_c.h}. Loads the native
 * library on first use and owns one {@code ww_artifact} + one
 * {@code ww_context_pool} shared by both the planner ({@link #query}) and the
 * executor ({@link #runExecutor}).</p>
 *
 * <h2>Loading the DLL</h2>
 * The library is located via {@link NativeCache#locateWorldWalkerDll()}:
 * the {@code -Dworldwalker.dll=<absolute path>} override first, then the
 * downloaded {@code ~/.botwithus/native/worldwalker.dll} entry. There is no
 * {@code System.loadLibrary} fallback — java-rules §Banned 2 (JNI) rules out
 * {@code loadLibrary} for project code; Panama is the supported path for FFI.
 *
 * <h2>Required JVM flags</h2>
 * The CLI {@code run} task must pass:
 * <pre>--enable-native-access=com.botwithus.bot.core</pre>
 * Without it, the first downcall throws an {@link IllegalCallerException}.
 *
 * <h2>Threading</h2>
 * A single instance is safe for concurrent {@link #query} and
 * {@link #runExecutor} calls — each call borrows its own search context from
 * the bounded pool. Sizing the pool below the number of concurrent caller
 * threads serialises borrows until a context is free; oversizing above
 * hardware concurrency wastes memory without speeding anything up. Sizes
 * around {@code Runtime.availableProcessors()} are the intended sweet spot.
 * {@code runExecutor} additionally blocks its caller for the duration of the
 * walk — see that method's javadoc for the virtual-thread caveat.
 */
public final class WorldWalker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorldWalker.class);

    private static final SymbolLookup LIB = locateLibrary();
    private static final WorldWalkerNative N = new WorldWalkerNative(LIB);

    private static SymbolLookup locateLibrary() {
        Path resolved = NativeCache.locateWorldWalkerDll().orElseThrow(() -> new IllegalStateException(
                "WorldWalker binary not located. Set -Dworldwalker.dll=<path> or place "
                        + NativeCache.WORLDWALKER_DLL_NAME + " under "
                        + "~/.botwithus/native/ (no System.loadLibrary fallback — see "
                        + "java-rules §Banned 2)."));
        log.debug("Loading {} from {}", NativeCache.WORLDWALKER_DLL_NAME, resolved);
        Arena scope = Arena.ofShared();
        return SymbolLookup.libraryLookup(resolved, scope);
    }

    private final MemorySegment artifact;
    private final MemorySegment pool;
    private volatile boolean closed;

    private WorldWalker(MemorySegment artifact, MemorySegment pool) {
        this.artifact = artifact;
        this.pool = pool;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Open a baked artifact and create a bounded search-context pool over it.
     *
     * @param artifactPath    on-disk path of a {@code wwbuild}-produced artifact
     * @param contextPoolSize number of reusable contexts (typically the host's
     *                        hardware concurrency); must be {@code > 0}
     * @throws IOException             when the artifact cannot be opened
     *                                 (missing, wrong version, corrupt)
     * @throws WorldWalkerException    when context-pool creation fails
     * @throws IllegalArgumentException for {@code contextPoolSize <= 0}
     */
    public static WorldWalker open(Path artifactPath, int contextPoolSize) throws IOException {
        Objects.requireNonNull(artifactPath, "artifactPath");
        if (contextPoolSize <= 0) {
            throw new IllegalArgumentException("contextPoolSize must be > 0");
        }
        MemorySegment art = openArtifact(artifactPath);
        MemorySegment poolSeg;
        try {
            poolSeg = createPool(art, contextPoolSize);
        } catch (RuntimeException e) {
            // Pool failed — close the artifact we just opened so we don't leak it.
            invokeVoid(N.wwArtifactClose, art);
            throw e;
        }
        return new WorldWalker(art, poolSeg);
    }

    private static MemorySegment openArtifact(Path artifactPath) throws IOException {
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment cstr = tmp.allocateFrom(artifactPath.toString());
            MemorySegment ptr;
            try {
                ptr = (MemorySegment) N.wwArtifactOpen.invokeExact(cstr);
            } catch (Throwable t) {
                throw rethrow(t);
            }
            if (ptr.address() == 0) {
                throw new IOException("ww_artifact_open failed: " + lastError());
            }
            return ptr;
        }
    }

    private static MemorySegment createPool(MemorySegment art, int count) {
        MemorySegment ptr;
        try {
            ptr = (MemorySegment) N.wwContextPoolCreate.invokeExact(art, (long) count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (ptr.address() == 0) {
            throw new WorldWalkerException("ww_context_pool_create failed: " + lastError());
        }
        return ptr;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Order matters: the pool borrows the artifact's reader, so the pool
        // must be destroyed before the artifact is closed.
        invokeVoid(N.wwContextPoolDestroy, pool);
        invokeVoid(N.wwArtifactClose, artifact);
    }

    // ── Query ──────────────────────────────────────────────────────────────

    /**
     * Plan a route from {@code start} to {@code goal}.
     *
     * <p>The goal's {@link WwGoal#radius()} field is currently ignored — the
     * planner plans to {@code (goal.x, goal.y, goal.plane)} exactly — and is
     * reserved for a future "plan to acceptance set" pass. Pass {@code null}
     * for {@code capabilities} (or {@link CapabilitySnapshot#empty()}) to
     * admit every requirement-gated transition.</p>
     *
     * @return the assembled path, or {@code null} when no route exists
     * @throws WorldWalkerException on invalid arguments or an unexpected
     *                              internal failure
     * @throws IllegalStateException when this handle has been closed
     */
    public WwPathResult query(WwTile start, WwGoal goal, CapabilitySnapshot capabilities) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        ensureOpen();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment startSeg = writeTile(tmp, start);
            MemorySegment goalSeg  = writeGoal(tmp, goal);
            MemorySegment capsSeg  = (capabilities == null || capabilities.isEmpty())
                    ? MemorySegment.NULL
                    : writeCapabilitySnapshot(tmp, capabilities);
            MemorySegment outPath  = tmp.allocate(WorldWalkerLayouts.WW_PATH);

            int rc;
            try {
                rc = (int) N.wwQuery.invokeExact(artifact, pool, startSeg, goalSeg, capsSeg, outPath);
            } catch (Throwable t) {
                throw rethrow(t);
            }

            if (rc == WorldWalkerLayouts.WW_ERR_NOT_FOUND) {
                return null;
            }
            if (rc != WorldWalkerLayouts.WW_OK) {
                throw new WorldWalkerException("ww_query rc=" + rc + ": " + lastError());
            }

            // Decode the result into heap-resident records, then release the
            // native steps buffer before returning. ww_path_free is null-safe
            // and zeroes the WwPath, so a half-decoded outPath stays consistent
            // even if step decoding throws.
            try {
                return decodePath(outPath);
            } finally {
                invokeVoid(N.wwPathFree, outPath);
            }
        }
    }

    // ── Executor ───────────────────────────────────────────────────────────

    /**
     * Plan a route from the player's live position to {@code goal} and walk
     * it, blocking the calling thread until arrival, failure, or
     * cancellation. The executor re-invokes the planner in-process on drift
     * or stuck deadlines using the same artifact + context pool this handle
     * owns; the call returns only when a terminal state is reached.
     *
     * <p>All ten {@link WwCallbacks} methods are invoked on the calling
     * thread. If any callback throws, the executor is cancelled at the next
     * safe point and the original {@link Throwable} is rethrown from this
     * method (preserving {@link Error} and {@link RuntimeException} as-is;
     * checked exceptions are wrapped in {@link WorldWalkerException}).</p>
     *
     * <p><b>Threading caveat.</b> This call blocks for the entire walk and
     * pins a virtual thread's carrier for that duration. If many clients walk
     * concurrently, schedule {@code runExecutor} on a platform thread (via
     * {@code Thread.ofPlatform()}) or raise
     * {@code -Djdk.virtualThreadScheduler.parallelism} above the default.</p>
     *
     * @return the terminal status of the run
     * @throws WorldWalkerException on an unexpected internal failure or when
     *                              a callback throws a checked exception
     * @throws IllegalStateException when this handle has been closed
     */
    public WwStatus runExecutor(WwGoal goal, WwCallbacks callbacks) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(callbacks, "callbacks");
        ensureOpen();
        Linker linker = Linker.nativeLinker();
        try (Arena tmp = Arena.ofConfined()) {
            MemorySegment goalSeg = writeGoal(tmp, goal);
            UpcallStubs.Run run = UpcallStubs.install(linker, tmp, callbacks);

            int rc;
            try {
                rc = (int) N.wwExecutorRun.invokeExact(
                        artifact, pool, goalSeg, run.callbacksStruct);
            } catch (Throwable t) {
                // If a callback also threw, surface that first — it's the
                // closer cause. invokeExact failures are far rarer and likely
                // structural.
                if (run.error() != null) {
                    throw rethrow(run.error());
                }
                throw rethrow(t);
            }

            if (run.error() != null) {
                throw rethrow(run.error());
            }
            return WwStatus.fromWire(rc);
        }
    }

    // ── Internals — segment writers ────────────────────────────────────────

    private static MemorySegment writeTile(Arena arena, WwTile tile) {
        MemorySegment seg = arena.allocate(WorldWalkerLayouts.WW_TILE);
        seg.set(JAVA_INT, 0, tile.x());
        seg.set(JAVA_INT, 4, tile.y());
        seg.set(JAVA_INT, 8, tile.plane());
        return seg;
    }

    private static MemorySegment writeGoal(Arena arena, WwGoal goal) {
        MemorySegment seg = arena.allocate(WorldWalkerLayouts.WW_GOAL);
        seg.set(JAVA_INT,  0, goal.x());
        seg.set(JAVA_INT,  4, goal.y());
        seg.set(JAVA_INT,  8, goal.plane());
        seg.set(JAVA_INT, 12, goal.radius());
        return seg;
    }

    private static MemorySegment writeCapabilitySnapshot(Arena arena, CapabilitySnapshot caps) {
        MemorySegment seg = arena.allocate(WorldWalkerLayouts.WW_CAPABILITY_SNAPSHOT);
        writeRun(arena, seg,  0, caps.skills());
        writeRun(arena, seg, 16, caps.items());
        writeRun(arena, seg, 32, caps.varbits());
        writeRun(arena, seg, 48, caps.varps());
        return seg;
    }

    /**
     * Write a {@code (ptr, count)} run pair into a {@link WorldWalkerLayouts#WW_CAPABILITY_SNAPSHOT}
     * at the given field offset. The {@code WwCapabilityEntry} array is
     * allocated into the same confined arena and freed alongside the snapshot
     * when the arena closes.
     */
    private static void writeRun(Arena arena, MemorySegment snapshot, long runOffset,
                                 Map<Integer, Integer> entries) {
        if (entries.isEmpty()) {
            snapshot.set(ADDRESS,   runOffset,     MemorySegment.NULL);
            snapshot.set(JAVA_LONG, runOffset + 8, 0L);
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

    // ── Internals — result decoder ─────────────────────────────────────────

    private static WwPathResult decodePath(MemorySegment outPath) {
        MemorySegment stepsPtr = outPath.get(ADDRESS, 0);
        long stepCount         = outPath.get(JAVA_LONG, 8);
        float cost             = outPath.get(JAVA_FLOAT, 16);

        if (stepCount < 0 || stepCount > Integer.MAX_VALUE) {
            throw new WorldWalkerException("ww_query stepCount out of range: " + stepCount);
        }
        int n = (int) stepCount;
        if (n == 0) {
            return new WwPathResult(List.of(), cost);
        }

        long stepBytes = (long) n * WorldWalkerLayouts.WW_STEP.byteSize();
        MemorySegment stepsView = stepsPtr.reinterpret(stepBytes);
        List<WwStep> steps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long base = (long) i * WorldWalkerLayouts.WW_STEP.byteSize();
            int kindWire = Byte.toUnsignedInt(stepsView.get(JAVA_BYTE, base));
            int plane    = Byte.toUnsignedInt(stepsView.get(JAVA_BYTE, base + 1));
            int targetX  = stepsView.get(JAVA_INT, base + 4);
            int targetY  = stepsView.get(JAVA_INT, base + 8);
            long transitionIndex = Integer.toUnsignedLong(stepsView.get(JAVA_INT, base + 12));
            steps.add(new WwStep(StepKind.fromWire(kindWire), plane, targetX, targetY, transitionIndex));
        }
        return new WwPathResult(steps, cost);
    }

    // ── Internals — error / invocation ────────────────────────────────────

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WorldWalker handle is closed");
        }
    }

    private static void invokeVoid(java.lang.invoke.MethodHandle mh, MemorySegment arg) {
        try {
            mh.invokeExact(arg);
        } catch (Throwable t) {
            // close() must not throw; log and continue. Other callers route
            // through rethrow which preserves Error / RuntimeException.
            log.debug("WorldWalker downcall failed", t);
        }
    }

    private static String lastError() {
        try {
            MemorySegment p = (MemorySegment) N.wwLastError.invokeExact();
            if (p.address() == 0) {
                return "";
            }
            return p.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "<lastError unavailable: " + t + ">";
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        return Throwables.rethrow(t,
                cause -> new WorldWalkerException("WorldWalker invocation failed", cause));
    }
}
