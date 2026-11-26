package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.runtime.SessionPacing;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Humanize;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The measurement is the agent's own. After each committed action,
 * {@code get_humanizer_session}'s {@code last} block reports, on the client clock, the press
 * the agent intended ({@code final_press_ms}: the planned press after any release-time re-aim)
 * and the frame the click actually went out ({@code delivered_press_ms}). The agent plans the
 * next press from the previous one, so the host's pacing shows up only as the excess of
 * delivered over final: an action queued after its press was due goes out late by however long
 * the host slept past the plan. {@code planned_press_ms} is not the reference, because about
 * one walk hold in three is re-aimed and lands after it for reasons that are not the host's.
 * An agent that predates {@code final_press_ms} falls back to {@code planned_press_ms}.</p>
 *
 * <p>Two arms, both a loop that queues one click and then sleeps
 * {@code Humanize.loopDelay(BASE_MS, VARIANCE)}:</p>
 * <ul>
 *   <li><b>deferring</b>: the runner's binding, so {@code loopDelay} waits for the agent;</li>
 *   <li><b>control</b>: {@link SessionPacing#INACTIVE} bound instead, which is what the host
 *       did before this change, so the two compound.</li>
 * </ul>
 * <p>What counts as a failure: the deferring arm's median excess over one server tick, or a
 * control arm that shows no more excess than the deferring arm (the measurement could not see
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
    /** Excess the deferring arm may show: the agent dispatches on the game tick. */
    private static final long TOLERANCE_MS = 600;
    private static final int WALK_OPTION = 1;
    private static final int EAST = 1;
    /** How long to wait for a queued action to commit before reading its interval. */
    private static final long COMMIT_WAIT_MS = 15_000;
    private static final long COMMIT_POLL_MS = 50;

    private RpcClient rpc;
    private GameAPI api;

    @BeforeAll
    void connect() {
        AgentSelection.Attach agent = LiveAgent.attachOrSkip();
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        api = new GameAPIImpl(rpc);
        log.info("connected to {}; session plan: {}", agent.pipeName(), api.sessionPacing());
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
    }

    @Test
    void layerActive_pressIntervalsFollowThePlanNotPlanPlusLoopDelay() {
        Assumptions.assumeTrue(api.sessionPacing().isActive(),
                "SKIPPED: the agent's session layer is not active, so there is no plan to follow");

        List<Long> control = excesses(() -> SessionPacing.INACTIVE);
        List<Long> deferring = excesses(api::sessionPacing);
        long controlMedian = median(control);
        long deferringMedian = median(deferring);
        log.info("excess of delivered over intended press (ms): deferring {} median {}; "
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

    /** One arm: how late each committed action's click went out against the agent's intent. */
    private List<Long> excesses(Supplier<SessionPacing> pacing) {
        LocalPlayer start = self();
        List<Long> out = new ArrayList<>();
        long committed = committedCount();
        for (int i = 0; i < ACTIONS_PER_ARM; i++) {
            int x = start.tileX() + (i % 2 == 0 ? EAST : 0);
            api.queueAction(new GameAction(ActionTypes.WALK, WALK_OPTION, x, start.tileY()));
            committed = awaitCommit(committed);
            out.add(lastExcess());
            int delay = ScopedValue.where(SessionPacing.CURRENT, pacing)
                    .call(() -> Humanize.loopDelay(BASE_MS, VARIANCE));
            sleep(delay);
        }
        return out.subList(1, out.size());
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

    private long awaitCommit(long before) {
        long deadline = System.currentTimeMillis() + COMMIT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            long now = committedCount();
            if (now > before) {
                return now;
            }
            sleep(COMMIT_POLL_MS);
        }
        throw new AssertionError("no action committed within " + COMMIT_WAIT_MS + " ms");
    }

    private long lastExcess() {
        Map<String, Object> last = MapHelper.getObjectMap(session(), "last");
        long intended = MapHelper.getLong(last, "final_press_ms");
        if (intended == 0) {
            intended = MapHelper.getLong(last, "planned_press_ms");
        }
        return MapHelper.getLong(last, "delivered_press_ms") - intended;
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
