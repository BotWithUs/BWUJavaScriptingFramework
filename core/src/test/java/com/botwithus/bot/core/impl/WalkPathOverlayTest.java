package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.core.worldwalker.StepKind;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwEventKind;
import com.botwithus.bot.core.worldwalker.WwPathResult;
import com.botwithus.bot.core.worldwalker.WwStep;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkPathOverlayTest {

    private static final long WALK_ID = 3L;
    private static final int GROUND = 0;
    private static final int UPSTAIRS = 1;
    private static final int BASE = 3200;
    private static final String KEY_PREFIX = "ww:3:";
    private static final String TARGET_KEY = "ww:3:target";
    private static final String FIRST_SEGMENT_KEY = "ww:3:seg:0";
    private static final String SECOND_SEGMENT_KEY = "ww:3:seg:1";
    private static final String THIRD_SEGMENT_KEY = "ww:3:seg:2";
    private static final WwTile START = new WwTile(BASE, BASE, GROUND);
    private static final WwTile CLICK = new WwTile(BASE + 1, BASE, GROUND);
    private static final List<WwStep> ROUTE = List.of(walk(1), walk(2), walk(3));
    private static final int MID_WALK_STEP = 5;
    private static final int CLICK_BURST = 5;
    private static final int KEEP_ALIVE_REDRAWS = 3;
    /** Long enough that no keep-alive fires inside a test that does not ask for one. */
    private static final long IDLE_KEEP_ALIVE_MS = 60_000L;
    private static final long FAST_KEEP_ALIVE_MS = 20L;
    private static final long WAIT_SECONDS = 5L;
    private static final long POLL_MS = 5L;

    private final RecordingSink sink = new RecordingSink();
    private final ScheduledExecutorService worker = newWorker();
    private final AtomicInteger plans = new AtomicInteger();
    private volatile boolean isEnabled = true;
    private volatile Optional<WwTile> here = Optional.of(START);
    private volatile RuntimeException planFailure;
    private volatile CountDownLatch planEntered = new CountDownLatch(0);
    private volatile CountDownLatch planRelease = new CountDownLatch(0);

    private WalkPathOverlay overlay = overlay(IDLE_KEEP_ALIVE_MS);

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    @Test
    void disabled_neitherPlansNorDraws() throws Exception {
        isEnabled = false;

        overlay.onClick(CLICK);
        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, 1));
        settle();

        assertAll(
                () -> assertEquals(0, plans.get()),
                () -> assertEquals(0, sink.drawAttempts.get()),
                () -> assertTrue(sink.onScreen.isEmpty()));
    }

    @Test
    void enabled_plansOnce_andDrawsTheRouteAndTheTarget() throws Exception {
        overlay.onClick(CLICK);
        settle();
        overlay.onClick(CLICK);
        settle();

        assertAll(
                () -> assertEquals(1, plans.get()),
                () -> assertEquals(2, sink.drawAttempts.get()),
                () -> assertEquals(Set.of(FIRST_SEGMENT_KEY, SECOND_SEGMENT_KEY, THIRD_SEGMENT_KEY,
                        TARGET_KEY), sink.onScreen));
    }

    @Test
    void stepAdvanced_dropsPassedSegments_withoutPlanningAgain() throws Exception {
        overlay.onClick(CLICK);
        settle();

        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, 2));
        settle();

        assertAll(
                () -> assertEquals(1, plans.get()),
                () -> assertEquals(Set.of(FIRST_SEGMENT_KEY, TARGET_KEY), sink.onScreen),
                () -> assertTrue(sink.cleared.contains(THIRD_SEGMENT_KEY)));
    }

    @Test
    void turnedOnMidWalk_drawsTheWholeFreshPlan_andCountsLaterStepsFromWhereItWasPlanned()
            throws Exception {
        isEnabled = false;
        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, MID_WALK_STEP));

        isEnabled = true;
        overlay.refresh();
        settle();
        Set<String> whenPlanned = Set.copyOf(sink.onScreen);
        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, MID_WALK_STEP + 1));
        settle();

        assertAll(
                () -> assertEquals(1, plans.get()),
                () -> assertEquals(Set.of(FIRST_SEGMENT_KEY, SECOND_SEGMENT_KEY, THIRD_SEGMENT_KEY),
                        whenPlanned, "the plan starts at the player, so none of it is passed yet"),
                () -> assertEquals(Set.of(FIRST_SEGMENT_KEY, SECOND_SEGMENT_KEY), sink.onScreen,
                        "one executor step later, one segment of the plan is passed"));
    }

    @Test
    void replanStarted_queriesTheRouteAgain_andCountsStepsFromZero() throws Exception {
        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, MID_WALK_STEP));
        settle();

        overlay.onEvent(event(WwEventKind.REPLAN_STARTED, WwEvent.INDEX_NA));
        settle();
        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, 1));
        settle();

        assertAll(
                () -> assertEquals(2, plans.get()),
                () -> assertEquals(Set.of(FIRST_SEGMENT_KEY, SECOND_SEGMENT_KEY), sink.onScreen));
    }

    @Test
    void anEventTheOverlayIgnores_costsNoRedraw() throws Exception {
        overlay.onClick(CLICK);
        settle();

        overlay.onEvent(event(WwEventKind.STUCK, 0));
        settle();

        assertEquals(1, sink.drawAttempts.get());
    }

    @Test
    void aBurstOfEvents_whileARedrawIsQueued_costsOneRedraw() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        worker.execute(() -> awaitQuietly(hold));

        for (int i = 0; i < CLICK_BURST; i++) {
            overlay.onClick(CLICK);
        }
        hold.countDown();
        settle();

        assertEquals(1, sink.drawAttempts.get());
    }

    @Test
    void arrived_clearsEverythingItDrew_andOnlyThat() throws Exception {
        overlay.onClick(CLICK);
        settle();

        overlay.onEvent(event(WwEventKind.ARRIVED, WwEvent.INDEX_NA));
        settle();

        assertAll(
                () -> assertTrue(sink.onScreen.isEmpty()),
                () -> assertTrue(sink.cleared.stream().allMatch(k -> k.startsWith(KEY_PREFIX))));
    }

    @Test
    void switchingTheSettingOff_clearsOnTheNextRefresh_andOnBringsItBack() throws Exception {
        overlay.onClick(CLICK);
        settle();

        isEnabled = false;
        overlay.refresh();
        settle();
        boolean wasCleared = sink.onScreen.isEmpty();
        isEnabled = true;
        overlay.refresh();
        settle();

        assertAll(
                () -> assertTrue(wasCleared),
                () -> assertTrue(sink.onScreen.contains(TARGET_KEY)));
    }

    @Test
    void close_clearsWhatIsDrawn_andShutsTheWorkerDown() throws Exception {
        overlay.onClick(CLICK);
        settle();

        overlay.close();

        assertAll(
                () -> assertTrue(worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)),
                () -> assertTrue(sink.onScreen.isEmpty()));
    }

    @Test
    void closedWhilePlanning_drawsNothing_andLeavesNothingOnScreen() throws Exception {
        gatePlanner();
        overlay.onClick(CLICK);
        assertTrue(planEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

        overlay.close();
        planRelease.countDown();

        assertAll(
                () -> assertTrue(worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)),
                () -> assertEquals(0, sink.drawAttempts.get()),
                () -> assertTrue(sink.onScreen.isEmpty()));
    }

    @Test
    void settingOffWhilePlanning_drawsNothing() throws Exception {
        gatePlanner();
        overlay.onClick(CLICK);
        assertTrue(planEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));

        isEnabled = false;
        overlay.refresh();
        planRelease.countDown();
        settle();

        assertAll(
                () -> assertEquals(0, sink.drawAttempts.get()),
                () -> assertTrue(sink.onScreen.isEmpty()));
    }

    @Test
    void aQuietWalk_isRedrawnBeforeItsTtlRunsOut() throws Exception {
        overlay = overlay(FAST_KEEP_ALIVE_MS);

        overlay.onClick(CLICK);

        assertTrue(waitFor(() -> sink.drawAttempts.get() >= KEEP_ALIVE_REDRAWS),
                "keep-alive redraws with no events");
        assertEquals(1, plans.get(), "a keep-alive redraw does not plan again");
    }

    @Test
    void aClearThatFails_isRetriedOnClose() throws Exception {
        overlay.onClick(CLICK);
        settle();
        sink.clearFailures = 2;

        overlay.onEvent(event(WwEventKind.ARRIVED, WwEvent.INDEX_NA));
        settle();
        boolean wasStillOnScreen = !sink.onScreen.isEmpty();
        overlay.close();

        assertAll(
                () -> assertTrue(wasStillOnScreen, "both clears failed; keys still held"),
                () -> assertTrue(worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)),
                () -> assertTrue(sink.onScreen.isEmpty(), "close retried the clear"));
    }

    @Test
    void aSinkFailure_neverReachesTheExecutor_andSwitchesDrawingOffForTheWalk() throws Exception {
        sink.failure = new IllegalStateException("unknown method debug_draw_set_batch");

        assertDoesNotThrow(() -> overlay.onClick(CLICK));
        settle();
        sink.failure = null;
        overlay.onEvent(event(WwEventKind.STEP_ADVANCED, 1));
        overlay.onClick(CLICK);
        settle();

        assertAll(
                () -> assertTrue(overlay.isBroken()),
                () -> assertEquals(1, sink.drawAttempts.get(), "no draw after the failure"),
                () -> assertTrue(sink.onScreen.isEmpty()));
    }

    @Test
    void aRefusedCommand_switchesDrawingOff_andClearsWhatWasSent() throws Exception {
        sink.refusals = 1;

        overlay.onClick(CLICK);
        settle();

        assertAll(
                () -> assertTrue(overlay.isBroken()),
                () -> assertTrue(sink.onScreen.isEmpty()));
    }

    @Test
    void aPlannerFailure_switchesDrawingOff() throws Exception {
        planFailure = new IllegalStateException("dynamic-region grid tore");

        assertDoesNotThrow(() -> overlay.onClick(CLICK));
        settle();

        assertAll(
                () -> assertTrue(overlay.isBroken()),
                () -> assertEquals(0, sink.drawAttempts.get()));
    }

    @Test
    void noPlayerYet_drawsNothing_andPlansOnceThePlayerIsThere() throws Exception {
        here = Optional.empty();
        overlay.onClick(CLICK);
        settle();
        int plansWithoutPlayer = plans.get();

        here = Optional.of(START);
        overlay.refresh();
        settle();

        assertAll(
                () -> assertEquals(0, plansWithoutPlayer),
                () -> assertEquals(1, plans.get()),
                () -> assertFalse(sink.onScreen.isEmpty()));
    }

    @Test
    void aPlayerOnAnotherPlane_seesNoneOfTheGroundRoute() throws Exception {
        here = Optional.of(new WwTile(BASE, BASE, UPSTAIRS));

        overlay.onClick(CLICK);
        settle();

        assertTrue(sink.onScreen.isEmpty());
    }

    /** Configured like the production worker: cancelled or delayed tasks never hold up shutdown. */
    private static ScheduledExecutorService newWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private WalkPathOverlay overlay(long keepAliveMs) {
        return new WalkPathOverlay(new WalkPathLayout(WALK_ID), this::plan, sink,
                () -> here, () -> isEnabled, worker, keepAliveMs);
    }

    private WwPathResult plan(WwTile start) {
        plans.incrementAndGet();
        planEntered.countDown();
        awaitQuietly(planRelease);
        RuntimeException failure = planFailure;
        if (failure != null) {
            throw failure;
        }
        return new WwPathResult(ROUTE, 0f);
    }

    /** Makes the next plan signal {@link #planEntered} and block until {@link #planRelease}. */
    private void gatePlanner() {
        planEntered = new CountDownLatch(1);
        planRelease = new CountDownLatch(1);
    }

    /** Waits until every redraw queued so far has run. */
    private void settle() throws InterruptedException, ExecutionException, TimeoutException {
        worker.submit(() -> { }).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static WwEvent event(WwEventKind kind, int stepIndex) {
        return new WwEvent(kind, kind.wireValue(), stepIndex, WwEvent.INDEX_NA);
    }

    private static WwStep walk(int dx) {
        return new WwStep(StepKind.WALK, GROUND, BASE + dx, BASE, WwStep.WALK_TRANSITION_SENTINEL);
    }

    /** Keeps what a producer would hold for this connection, and can fail or refuse on demand. */
    private static final class RecordingSink implements WalkPathOverlay.Sink {
        private final AtomicInteger drawAttempts = new AtomicInteger();
        private final List<String> cleared = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> onScreen = Collections.synchronizedSet(new HashSet<>());
        private volatile RuntimeException failure;
        private volatile int refusals;
        private volatile int clearFailures;

        @Override
        public DrawBatchResult draw(List<DrawCommand> commands) {
            drawAttempts.incrementAndGet();
            RuntimeException toThrow = failure;
            if (toThrow != null) {
                throw toThrow;
            }
            commands.forEach(c -> onScreen.add(c.key()));
            return new DrawBatchResult(commands.size() - refusals, refusals, "");
        }

        @Override
        public void clear(List<String> keys) {
            if (clearFailures > 0) {
                clearFailures--;
                throw new IllegalStateException("pipe closed");
            }
            cleared.addAll(keys);
            keys.forEach(onScreen::remove);
        }
    }
}
