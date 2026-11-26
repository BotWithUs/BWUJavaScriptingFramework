package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.runtime.SessionPacing;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Humanize;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host pacing against a real agent's session layer: with the layer active, the time between
 * presses follows the agent's plan and not plan + {@link Humanize#loopDelay}; with it
 * inactive, {@code loopDelay} is unchanged.
 *
 * <p>The measurement is the agent's own. The session layer anchors each action's plan at the
 * previous press's release. When the action reaches it too late for that, the plan is moved up
 * to the action's arrival and {@code get_humanizer_session}'s {@code last.late_ms} reports by how
 * much: that is exactly the time the host added on top of the plan. An action that arrives early
 * is held until its planned press, so its {@code late_ms} is 0. {@code delivered_press_ms} minus
 * {@code planned_press_ms} cannot show this, because the planned press is the one already moved.</p>
 *
 * <p>Two arms, both a loop that queues one click and then sleeps
 * {@code Humanize.loopDelay(BASE_MS, VARIANCE)}:</p>
 * <ul>
 *   <li><b>deferring</b>: the runner's binding, so {@code loopDelay} waits for the agent;</li>
 *   <li><b>control</b>: {@link SessionPacing#INACTIVE} bound instead, which is what the host
 *       did before this change, so the two compound.</li>
 * </ul>
 * <p>What counts as a failure: the deferring arm's median {@code late_ms} over one server tick,
 * or a control arm whose median is not above the deferring arm's (the measurement could not see
 * compounding, so a pass would mean nothing).</p>
 *
 * <p><b>This test moves the character</b>: it clicks between the tile the player stands on and
 * the one east of it. Gated behind {@code -Dbotwithus.live.sessionPacing=true}; use a
 * disposable account standing on open ground.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.live.sessionPacing", matches = "true")
class LiveSessionPacingTest {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionPacingTest.class);

    private static final String METHOD = "get_humanizer_session";
    /** Longer than a typical planned gap, so a host that sleeps it visibly overshoots the plan. */
    private static final int BASE_MS = 1_800;
    private static final double VARIANCE = 0.3;
    private static final int ACTIONS_PER_ARM = 12;
    /** {@code late_ms} the deferring arm may show: the agent dispatches on the game tick. */
    private static final long TOLERANCE_MS = 600;
    private static final int WALK_OPTION = 1;
    private static final int EAST = 1;
    /** Attempts per arm before giving up on collecting {@link #ACTIONS_PER_ARM} samples. */
    private static final int MAX_ATTEMPTS_PER_ARM = 2 * ACTIONS_PER_ARM;
    /** How long a queued action may stay held: a planned break legitimately runs past 30 s. */
    private static final long DRAIN_WAIT_MS = 120_000;
    private static final long DRAIN_POLL_MS = 50;
    /** How long the queue must sit drained with nothing held before an action counts as unplanned. */
    private static final long SETTLE_MS = 1_500;

    private SharedRegion region;
    private RpcClient rpc;
    private GameAPI api;

    @BeforeAll
    void connect() throws IOException {
        AgentSelection.Attach agent = LiveAgent.attachOrSkip();
        region = SharedRegion.open(agent.pid());
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        api = new GameAPIImpl(rpc, null, () -> new GameSnapshotImpl(region.snapshot()));
        log.info("connected to {}; session plan: {}", agent.pipeName(), api.sessionPacing());
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    void layerActive_pressIntervalsFollowThePlanNotPlanPlusLoopDelay() {
        Assumptions.assumeTrue(api.sessionPacing().isActive(),
                "SKIPPED: the agent's session layer is not active, so there is no plan to follow");

        List<Long> control = lateness(() -> SessionPacing.INACTIVE);
        List<Long> deferring = lateness(api::sessionPacing);
        long controlMedian = median(control);
        long deferringMedian = median(deferring);
        log.info("late_ms per action: deferring {} median {}; "
                + "control {} median {}", deferring, deferringMedian, control, controlMedian);

        assertTrue(controlMedian > deferringMedian,
                "the control arm must show the compounding, or this test cannot see it");
        assertTrue(deferringMedian <= TOLERANCE_MS,
                "with the layer active the host still adds " + deferringMedian + " ms per gap");
    }

    @Test
    void layerInactive_loopDelayIsUnchanged() {
        Assumptions.assumeFalse(api.sessionPacing().isActive(),
                "SKIPPED: the layer is active; this arm needs an agent without it");
        Supplier<SessionPacing> source = api::sessionPacing;
        for (int i = 0; i < ACTIONS_PER_ARM; i++) {
            int delay = ScopedValue.where(SessionPacing.CURRENT, source)
                    .call(() -> Humanize.loopDelay(BASE_MS, VARIANCE));
            assertTrue(delay >= BASE_MS / 2, "old floor kept: " + delay);
        }
        assertFalse(api.sessionPacing().isActive());
    }

    /**
     * One arm: how far each committed action's plan was moved up because the host queued it
     * late. An action the layer did not plan (the first one on a fresh client takes the
     * single-point move) contributes no sample.
     */
    private List<Long> lateness(Supplier<SessionPacing> pacing) {
        LocalPlayer start = self();
        walkAndAwait(start, 0);
        List<Long> out = new ArrayList<>();
        for (int i = 1; i <= MAX_ATTEMPTS_PER_ARM && out.size() < ACTIONS_PER_ARM; i++) {
            if (walkAndAwait(start, i)) {
                out.add(MapHelper.getLong(MapHelper.getObjectMap(session(), "last"), "late_ms"));
            }
            int delay = ScopedValue.where(SessionPacing.CURRENT, pacing)
                    .call(() -> Humanize.loopDelay(BASE_MS, VARIANCE));
            sleep(delay);
        }
        assertTrue(out.size() > 1, "the layer committed " + out.size() + " walks; nothing to measure");
        return out.subList(1, out.size());
    }

    /**
     * Clicks the start tile or the one east of it and waits until the layer commits it (true),
     * or until the queue has drained with nothing held for {@link #SETTLE_MS} (false: the
     * action was not planned). A planned break can hold an action past 30 s.
     */
    private boolean walkAndAwait(LocalPlayer start, int step) {
        long before = committedCount();
        int x = start.tileX() + (step % 2 == 0 ? EAST : 0);
        api.queueAction(new GameAction(ActionTypes.WALK, WALK_OPTION, x, start.tileY()));
        long deadline = System.currentTimeMillis() + DRAIN_WAIT_MS;
        long idleSince = Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> now = session();
            if (MapHelper.getLong(MapHelper.getObjectMap(now, "counters"), "committed") > before) {
                return true;
            }
            boolean isIdle = !MapHelper.getBool(now, "held") && api.getActionQueueSize() == 0;
            idleSince = isIdle ? Math.min(idleSince, System.currentTimeMillis()) : Long.MAX_VALUE;
            if (System.currentTimeMillis() - idleSince > SETTLE_MS) {
                return false;
            }
            sleep(DRAIN_POLL_MS);
        }
        throw new AssertionError("the walk was not committed within " + DRAIN_WAIT_MS
                + " ms; session: " + session());
    }

    private LocalPlayer self() {
        GameSnapshot snapshot = api.snapshot();
        Assumptions.assumeTrue(snapshot != null && snapshot.self() != null,
                "SKIPPED: not in the world");
        return snapshot.self();
    }

    private Map<String, Object> session() {
        return rpc.callSync(METHOD, Map.of());
    }

    private long committedCount() {
        return MapHelper.getLong(MapHelper.getObjectMap(session(), "counters"), "committed");
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
