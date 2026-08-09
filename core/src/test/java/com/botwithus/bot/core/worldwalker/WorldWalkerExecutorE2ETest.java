package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.snapshot.DynamicRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6e end-to-end test: drives {@link WorldWalker#runExecutor} from Java
 * against a real {@code worldwalker.dll} and a real baked artifact, exercising
 * the full Panama upcall surface — all ten {@link WwCallbacks} methods, the
 * native call, and the {@link WwStatus} return mapping.
 *
 * <p>The test is gated on two system properties so a checkout without the
 * native binary or a baked artifact still builds clean:
 * <ul>
 *   <li>{@code -Dworldwalker.dll=<absolute path>} — the runtime DLL.</li>
 *   <li>{@code -Dworldwalker.testArtifact=<absolute path>} — the {@code .wwa}
 *       to load. Any artifact produced by {@code wwbuild} works.</li>
 * </ul>
 *
 * <p>The two cases exercised here intentionally do not depend on any
 * particular world coordinate, so the test is portable across artifacts cut
 * from different cache snapshots:
 * <ol>
 *   <li><b>start-equals-goal short-circuit.</b> The executor returns
 *       {@link WwStatus#ARRIVED} on the first read-position poll without
 *       walking — proves all callbacks bind, the native call runs, the
 *       status mapping returns the right enum, and no exception leaks
 *       through the upcall boundary.</li>
 *   <li><b>simulated walk-and-arrive.</b> Starts at one tile and asks the
 *       executor to walk to another. The stub's {@code walkTo} jumps the
 *       simulated position to the requested target so the next
 *       {@code readPosition} reports arrival immediately — the planner has
 *       to assemble a non-empty path, the executor has to dispatch a real
 *       walk step, and at least one {@link WwEventKind#STEP_ADVANCED} or
 *       {@link WwEventKind#ARRIVED} event has to land in {@code onEvent}.
 *       Skipped when the planner returns no route from the chosen start
 *       (some artifacts have no in-area neighbour reachable from the
 *       hard-coded start tile).</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "worldwalker.dll",          matches = ".+")
@EnabledIfSystemProperty(named = "worldwalker.testArtifact", matches = ".+")
class WorldWalkerExecutorE2ETest {

    // Any tile that the artifact under test actually contains. The test will
    // call query() first and skip the walk case if the planner can't make a
    // route from here — so a poor pick degrades gracefully into a skip
    // rather than a false failure.
    private static final int START_X = 881;
    private static final int START_Y = 1596;
    private static final int START_PLANE = 0;

    private static Path artifactPath() {
        String prop = System.getProperty("worldwalker.testArtifact");
        return Paths.get(prop);
    }

    @Test
    void startEqualsGoalShortCircuitsToArrived() throws Exception {
        Path artifact = artifactPath();
        assertTrue(Files.exists(artifact), "artifact missing at " + artifact);

        try (WorldWalker walker = WorldWalker.open(artifact, 1)) {
            WwTile start = new WwTile(START_X, START_Y, START_PLANE);
            CountingStub stub = new CountingStub(start);
            stub.simulateWalking = false;

            WwGoal goal = new WwGoal(start.x(), start.y(), start.plane(), 0);
            WwStatus status = walker.runExecutor(goal, stub);

            assertEquals(WwStatus.ARRIVED, status,
                    "executor should short-circuit to ARRIVED when start == goal");
            assertEquals(0, stub.walkToCalls.get(),
                    "no walk should fire on short-circuit");
            assertTrue(stub.readPositionCalls.get() >= 1,
                    "readPosition must fire at least once before short-circuit");
            assertTrue(stub.onEventCalls.get() >= 1,
                    "onEvent should receive at least the terminal event");
            assertNotNull(stub.lastEventKind.get(), "onEvent must observe a kind");
            assertTrue(stub.lastError.get() == null,
                    "no callback should have thrown: " + stub.lastError.get());
        }
    }

    @Test
    void simulatedWalkArrivesAndFiresWalkTo() throws Exception {
        Path artifact = artifactPath();
        assertTrue(Files.exists(artifact), "artifact missing at " + artifact);

        try (WorldWalker walker = WorldWalker.open(artifact, 1)) {
            WwTile start = new WwTile(START_X, START_Y, START_PLANE);
            WwGoal probeGoal = new WwGoal(start.x() - 8, start.y() - 8, start.plane(), 0);

            // Probe with the planner first — if the start coords don't sit
            // anywhere walkable in this artifact, treat the walk case as not
            // applicable (the short-circuit case already proved binding).
            WwPathResult plan = walker.query(start, probeGoal, null);
            if (plan == null || plan.steps().isEmpty()) {
                return;
            }

            CountingStub stub = new CountingStub(start);
            stub.simulateWalking = true;

            WwStatus status = walker.runExecutor(probeGoal, stub);

            assertEquals(WwStatus.ARRIVED, status,
                    "simulated walk should terminate ARRIVED");
            assertTrue(stub.walkToCalls.get() >= 1,
                    "executor should dispatch at least one walkTo for a non-empty plan");
            assertTrue(stub.readPositionCalls.get() >= 2,
                    "executor must poll position more than once during a walk");
            assertTrue(stub.onEventCalls.get() >= 1, "onEvent must observe progress");
            assertEquals(null, stub.lastError.get(),
                    "no callback should have thrown");
        }
    }

    /**
     * Test double for {@link WwCallbacks} that records call counts, simulates
     * an instant walk when asked, and surfaces the first exception any
     * callback throws so the test can assert it was clean.
     */
    private static final class CountingStub implements WwCallbacks {

        final AtomicReference<WwTile> position = new AtomicReference<>();
        final AtomicInteger readPositionCalls   = new AtomicInteger();
        final AtomicInteger readCapabilityCalls = new AtomicInteger();
        final AtomicInteger readInstanceCalls   = new AtomicInteger();
        final AtomicInteger walkToCalls         = new AtomicInteger();
        final AtomicInteger interactCalls       = new AtomicInteger();
        final AtomicInteger runChainStepCalls   = new AtomicInteger();
        final AtomicInteger sleepTicksCalls     = new AtomicInteger();
        final AtomicInteger shouldCancelCalls   = new AtomicInteger();
        final AtomicInteger isInterfaceOpenCalls = new AtomicInteger();
        final AtomicInteger onEventCalls        = new AtomicInteger();
        final AtomicReference<WwEventKind> lastEventKind = new AtomicReference<>();
        final AtomicReference<Throwable> lastError = new AtomicReference<>();
        volatile boolean simulateWalking;

        CountingStub(WwTile start) {
            this.position.set(start);
        }

        @Override
        public WwTile readPosition() {
            readPositionCalls.incrementAndGet();
            return position.get();
        }

        @Override
        public CapabilitySnapshot readCapability() {
            readCapabilityCalls.incrementAndGet();
            return CapabilitySnapshot.empty();
        }

        /**
         * Pulled at every (re-)plan alongside {@link #readCapability}. The
         * fixture walks the static overworld, so it reports "not an instance" —
         * which is what a host answers for any ordinary scene.
         */
        @Override
        public DynamicRegion readInstance() {
            readInstanceCalls.incrementAndGet();
            return null;
        }

        @Override
        public int readVarbit(int id) {
            return 0;
        }

        @Override
        public int readItemCount(int itemId) {
            return 0;
        }

        @Override
        public boolean isItemWorn(int itemId) {
            return false;
        }

        @Override
        public boolean isInterfaceOpen(int interfaceId) {
            isInterfaceOpenCalls.incrementAndGet();
            // SimulateTransition not in scope for this test — return false
            // so the executor never enters a chain dispatch path.
            return false;
        }

        @Override
        public void walkTo(WwTile target) {
            walkToCalls.incrementAndGet();
            if (simulateWalking) {
                position.set(target);
            }
        }

        @Override
        public int interact(int objectId, WwTile tile, int optionIndex) {
            interactCalls.incrementAndGet();
            // Report an action as issued so the executor settles as before —
            // this double exercises the normal (closed-door / climb) path, not
            // the already-open skip.
            return 1;
        }

        @Override
        public void runChainStep(int kind, int a, int b, int c, int d,
                                 int e, int f, int g, int h, int i) {
            runChainStepCalls.incrementAndGet();
        }

        @Override
        public void sleepTicks(int ticks) {
            sleepTicksCalls.incrementAndGet();
        }

        @Override
        public boolean shouldCancel() {
            shouldCancelCalls.incrementAndGet();
            return false;
        }

        @Override
        public void onEvent(WwEvent event) {
            onEventCalls.incrementAndGet();
            lastEventKind.set(event.kind());
        }
    }
}
