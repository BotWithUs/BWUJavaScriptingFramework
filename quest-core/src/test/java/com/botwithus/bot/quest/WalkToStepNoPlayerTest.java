package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.quest.steps.WalkToStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A walk step run through the router across a window with no local player. The player is absent
 * during login, loading and world hops, so that must not spend the retry budget.
 */
class WalkToStepNoPlayerTest {

    private static final int TARGET_X = 3200;
    private static final int TARGET_Y = 3201;
    private static final int FAR_X = 3300;
    private static final int STOPPED = -1;
    private static final int TICK_DELAY_MS = 600;
    /** Comfortably more ticks than the retry budget would allow. */
    private static final int WINDOW_TICKS = QuestRouterTask.MAX_RETRIES * 4;
    private static final Duration SHORT_LIMIT = Duration.ofMillis(100);
    private static final long LIMIT_WAIT_MS = SHORT_LIMIT.toMillis() + 50;
    private static final long GIVE_UP_MS = 10_000;
    private static final long TICK_SLEEP_MS = 5;
    private static final String ABORT_REASON_KEY = "quest.abort_reason";

    private final GameSnapshot snapshot = mock(GameSnapshot.class);
    private final Navigation navigation = mock(Navigation.class);
    private final ScriptContextPublisher publisher = mock(ScriptContextPublisher.class);
    private final ScriptContext context = mock(ScriptContext.class);
    private final QuestProgressTracker tracker = mock(QuestProgressTracker.class);

    @BeforeEach
    void wireTheContext() {
        GameAPI api = mock(GameAPI.class);
        when(api.snapshot()).thenReturn(snapshot);
        when(context.getGameAPI()).thenReturn(api);
        when(context.getNavigation()).thenReturn(navigation);
        when(context.getScriptContext()).thenReturn(publisher);
        when(tracker.current()).thenReturn(new QuestState(Map.of()));
    }

    @Test
    void theDefaultLimitIsAMinute() {
        assertEquals(Duration.ofSeconds(60), WalkToStep.DEFAULT_NO_PLAYER_LIMIT);
    }

    @Test
    void noPlayer_doesNotSpendTheRetryBudget() {
        QuestRouterTask router = routerFor(new WalkToStep(TARGET_X, TARGET_Y, 0));

        tickThroughAWindow(router);

        assertTrue(router.validate());
        verify(navigation, never()).walkWorldPath(anyInt(), anyInt(), anyInt(), anyLong());
        verify(publisher, never()).trace(eq("WARN"), contains("retry"));
    }

    @Test
    void aShortWindow_isSurvived_andTheWalkThenRuns() {
        QuestRouterTask router = routerFor(new WalkToStep(TARGET_X, TARGET_Y, 0));
        tickThroughAWindow(router);
        when(snapshot.self()).thenReturn(playerAt(FAR_X));
        when(navigation.walkWorldPath(eq(TARGET_X), eq(TARGET_Y), eq(0), anyLong()))
                .thenAnswer(call -> {
                    when(snapshot.self()).thenReturn(playerAt(TARGET_X));
                    return WalkResult.ARRIVED;
                });

        assertEquals(TICK_DELAY_MS, router.execute());

        assertTrue(router.validate());
    }

    @Test
    void anAbsenceOutlastingTheLimit_stopsWithAClearReason() throws Exception {
        QuestRouterTask router = routerFor(
                new WalkToStep(TARGET_X, TARGET_Y, 0).withNoPlayerLimit(SHORT_LIMIT));
        long started = System.nanoTime();

        tickUntilStopped(router);

        assertTrue(System.nanoTime() - started >= SHORT_LIMIT.toNanos(), "stopped before the limit");
        assertFalse(router.validate());
        verify(publisher).annotation(eq(ABORT_REASON_KEY), contains("no local player"));
    }

    @Test
    void aPlayerLostDuringTheWalk_waitsRatherThanRetrying() {
        QuestRouterTask router = routerFor(new WalkToStep(TARGET_X, TARGET_Y, 0));
        when(snapshot.self()).thenReturn(playerAt(FAR_X));
        when(navigation.walkWorldPath(eq(TARGET_X), eq(TARGET_Y), eq(0), anyLong()))
                .thenAnswer(call -> {
                    when(snapshot.self()).thenReturn(null);
                    return WalkResult.FAILED;
                });

        router.execute();

        verify(publisher, never()).trace(eq("WARN"), contains("retry"));
        assertTrue(router.validate());
    }

    @Test
    void aPlayerComingBack_restartsTheLimit() throws Exception {
        QuestRouterTask router = routerFor(
                new WalkToStep(TARGET_X, TARGET_Y, 0).withNoPlayerLimit(SHORT_LIMIT));
        when(navigation.walkWorldPath(eq(TARGET_X), eq(TARGET_Y), eq(0), anyLong()))
                .thenReturn(WalkResult.FAILED);
        router.execute();

        when(snapshot.self()).thenReturn(playerAt(FAR_X));
        router.execute();
        when(snapshot.self()).thenReturn(null);
        Thread.sleep(LIMIT_WAIT_MS);

        assertTrue(router.execute() > 0, "the limit counted the time the player was present");
    }

    @Test
    void aWalkFailingWithAPlayerPresent_stillUsesTheRetryBudget() {
        QuestRouterTask router = routerFor(new WalkToStep(TARGET_X, TARGET_Y, 0));
        when(snapshot.self()).thenReturn(playerAt(FAR_X));
        when(navigation.walkWorldPath(eq(TARGET_X), eq(TARGET_Y), eq(0), anyLong()))
                .thenReturn(WalkResult.FAILED);

        for (int i = 0; i < QuestRouterTask.MAX_RETRIES; i++) {
            assertTrue(router.execute() > 0);
        }

        assertEquals(STOPPED, router.execute());
        verify(publisher).annotation(eq(ABORT_REASON_KEY), contains("max retries"));
    }

    private QuestRouterTask routerFor(WalkToStep step) {
        QuestId quest = new QuestId(1, "quest", List.of());
        return new QuestRouterTask(quest, tracker, List.of(step), context);
    }

    private static void tickThroughAWindow(QuestRouterTask router) {
        for (int i = 0; i < WINDOW_TICKS; i++) {
            assertTrue(router.execute() > 0, "stopped during the window at tick " + i);
        }
    }

    private static void tickUntilStopped(QuestRouterTask router) throws InterruptedException {
        long giveUpAt = System.currentTimeMillis() + GIVE_UP_MS;
        while (router.execute() != STOPPED) {
            assertTrue(System.currentTimeMillis() < giveUpAt, "never stopped");
            Thread.sleep(TICK_SLEEP_MS);
        }
    }

    private static LocalPlayer playerAt(int x) {
        return new LocalPlayer(0, 100, x, TARGET_Y, 0, 0, -1, -1, 0, -1, 0, true, -1, 99, 99, List.of());
    }
}
