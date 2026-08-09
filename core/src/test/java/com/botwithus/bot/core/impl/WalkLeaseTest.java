package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The movement-lease contention contract: one script drives the character's
 * feet at a time, a second script's request is refused rather than allowed to
 * interleave with it, and every script reads back its own outcome.
 *
 * <p>Entirely headless. The lease decision is taken before the pathfinder is
 * ever opened, so none of this needs the native library, an artifact, or a game
 * client — which is the reason the acquire happens where it does.</p>
 */
class WalkLeaseTest {

    private static final int A_X = 3200;
    private static final int A_Y = 3200;
    private static final int B_X = 2500;
    private static final int B_Y = 3100;

    /** Scripts piled onto one character at once in the contention case. */
    private static final int CONTENDERS = 8;

    /**
     * Rounds the contenders re-race each other for. The window a tie has to
     * land in is the handful of instructions between reading the lease slot and
     * setting it, so one round proves nothing — repetition is what turns the
     * compare-and-set from incidental into tested.
     */
    private static final int CONTENDED_ROUNDS = 2_000;

    /**
     * Acquire/release cycles the status prober runs against. The gap it hunts
     * for is a few instructions wide, so this trades a few milliseconds for a
     * realistic chance of landing in it.
     */
    private static final int STATUS_PROBE_ROUNDS = 20_000;

    /** Ceiling on any wait in this test; nothing here should approach it. */
    private static final long TEST_TIMEOUT_MS = 10_000L;

    private ScriptGate gate;
    private GameAPIImpl api;

    @BeforeEach
    void setUp() {
        gate = new ScriptGate();
        api = new GameAPIImpl(mock(RpcClient.class));
        api.setScriptGate(gate);
    }

    // ============================== Refusal ==============================

    @Test
    void secondScriptIsRefusedWhileAnotherWalks() {
        assertNotNull(acquireAs("A", A_X, A_Y));

        assertNull(acquireAs("B", B_X, B_Y), "B must not be handed a lease A is holding");
    }

    @Test
    void refusalLeavesTheIncumbentWalking() {
        acquireAs("A", A_X, A_Y);
        acquireAs("B", B_X, B_Y);

        WalkStatus a = statusAs("A");
        assertEquals(WalkState.WALKING.wireName(), a.state());
        assertTrue(a.isWalking());
        assertEquals(A_X, a.targetX());
        assertEquals(A_Y, a.targetY());
    }

    @Test
    void refusedScriptReadsBackItsOwnRefusal() {
        acquireAs("A", A_X, A_Y);
        acquireAs("B", B_X, B_Y);

        WalkStatus b = statusAs("B");
        assertEquals(WalkState.REFUSED_BUSY.wireName(), b.state());
        assertFalse(b.isWalking());
        assertTrue(b.isDone(), "a refusal is a conclusion, not a walk still in flight");
        assertEquals(B_X, b.targetX());
        assertEquals(B_Y, b.targetY());
    }

    // ============================== Legal takeovers ==============================

    @Test
    void ownerMayPreemptItsOwnWalk() {
        WalkLease first = acquireAs("A", A_X, A_Y);
        WalkLease second = acquireAs("A", B_X, B_Y);

        assertNotNull(second, "re-targeting your own walk must stay legal");
        assertNotSame(first, second);
        assertTrue(first.cancel().get(), "the preempted walk must have been cancelled");
        assertEquals(B_X, statusAs("A").targetX());
    }

    @Test
    void hostMayTakeTheWalkFromAScript() {
        acquireAs("A", A_X, A_Y);

        assertNotNull(acquireAsHost(), "an untagged caller carries host authority");
    }

    @Test
    void revokedIncumbentLosesTheLease() {
        WalkLease stranded = acquireAs("A", A_X, A_Y);
        gate.revoke("A");

        assertNotNull(acquireAs("B", B_X, B_Y),
                "a revoked script never releases its own lease, so it must be reclaimable");
        assertTrue(stranded.cancel().get());
    }

    // ============================== Cancellation ==============================

    @Test
    void cancelFromAnotherScriptIsIgnored() {
        acquireAs("A", A_X, A_Y);

        runAs("B", () -> api.walkCancel());

        assertEquals(WalkState.WALKING.wireName(), statusAs("A").state());
    }

    @Test
    void cancelByTheOwnerReleasesTheLease() {
        acquireAs("A", A_X, A_Y);

        runAs("A", () -> api.walkCancel());

        assertEquals(WalkState.CANCELLED.wireName(), statusAs("A").state());
        assertNotNull(acquireAs("B", B_X, B_Y), "the character is free once A lets go");
    }

    @Test
    void hostCancelStopsAnyScriptsWalk() {
        acquireAs("A", A_X, A_Y);

        runAsHost(() -> api.walkCancel());

        assertEquals(WalkState.CANCELLED.wireName(), statusAs("A").state());
    }

    // ============================== Per-owner status ==============================

    @Test
    void eachScriptReadsItsOwnOutcomeNotTheOthers() {
        acquireAs("A", A_X, A_Y);
        acquireAs("B", B_X, B_Y);
        runAs("A", () -> api.walkCancel());

        assertEquals(WalkState.CANCELLED.wireName(), statusAs("A").state());
        assertEquals(WalkState.REFUSED_BUSY.wireName(), statusAs("B").state());
    }

    @Test
    void aScriptThatNeverWalkedIsIdle() {
        WalkStatus status = statusAs("A");

        assertEquals(WalkState.IDLE.wireName(), status.state());
        assertFalse(status.isWalking());
        assertFalse(status.isDone());
    }

    @Test
    void hostSeesWhicheverWalkIsInProgress() {
        acquireAs("A", A_X, A_Y);

        WalkStatus host = callAsHost(() -> api.getWalkStatus());
        assertEquals(WalkState.WALKING.wireName(), host.state());
        assertEquals(A_X, host.targetX());
    }

    // ============================== Refusal count ==============================

    @Test
    void refusalCountIsCallerScopedAndCountsOnlyRefusals() {
        acquireAs("A", A_X, A_Y);
        acquireAs("B", B_X, B_Y);

        assertEquals(1L, refusalCountAs("B"));
        assertEquals(0L, refusalCountAs("A"), "the script that got the lease was not refused");
        assertEquals(0L, refusalCountAsHost(), "the host has its own tally, not B's");
    }

    @Test
    void refusalCountRisesOncePerRefusedRequest() {
        acquireAs("A", A_X, A_Y);

        acquireAs("B", B_X, B_Y);
        acquireAs("B", B_X, B_Y);

        assertEquals(2L, refusalCountAs("B"));
    }

    // ============================== Lease / worker handoff ==============================

    @Test
    void workerIsStartedWhileTheLeaseIsStillHeld() throws Exception {
        WalkLease lease = acquireAs("A", A_X, A_Y);
        CountDownLatch ran = new CountDownLatch(1);
        Thread worker = new Thread(ran::countDown, "ww-executor-stub");

        assertTrue(api.startWalkWorker(lease, worker));
        assertTrue(ran.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertSame(worker, lease.worker().get());
    }

    @Test
    void workerIsNotStartedWhenTheLeaseWasCancelledFirst() {
        WalkLease lease = acquireAs("A", A_X, A_Y);
        // Stands in for a cancel that lands while the caller is still opening
        // the pathfinder — the lease is up, but there is no thread to join yet.
        runAsHost(() -> api.walkCancel());
        AtomicBoolean ran = new AtomicBoolean();
        Thread worker = new Thread(() -> ran.set(true), "ww-executor-stub");

        assertFalse(api.startWalkWorker(lease, worker),
                "a walk cancelled before its executor existed must never start one");
        assertFalse(worker.isAlive());
        assertFalse(ran.get());
    }

    @Test
    void aSecondScriptCannotWalkWhileTheFirstIsStillStarting() {
        WalkLease stalled = acquireAs("A", A_X, A_Y);
        // A is still building its executor, so its lease has no worker yet.
        assertNull(stalled.worker().get());

        assertNull(acquireAs("B", B_X, B_Y),
                "B must not slip in through the window before A's executor exists");
    }

    @Test
    void cancelJoinsARunningWorkerBeforeHandingTheLeaseBack() throws Exception {
        WalkLease lease = acquireAs("A", A_X, A_Y);
        CountDownLatch running = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            running.countDown();
            while (!lease.cancel().get()) {
                Thread.onSpinWait();
            }
        }, "ww-executor-stub");
        assertTrue(api.startWalkWorker(lease, worker));
        assertTrue(running.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        runAs("A", () -> api.walkCancel());

        assertFalse(worker.isAlive(), "walkCancel must not return while the executor is still running");
        assertEquals(WalkState.CANCELLED.wireName(), statusAs("A").state());
    }

    // ============================== Outcome ordering ==============================

    @Test
    void aLateWorkerCannotStampOverANewerOutcome() {
        WalkLease first = acquireAs("A", A_X, A_Y);
        api.releaseWalkLease(first, WalkState.ARRIVED);
        WalkLease second = acquireAs("A", B_X, B_Y);
        api.releaseWalkLease(second, WalkState.FAILED);

        // The first walk's executor finally reports, long after it lost the lease.
        api.releaseWalkLease(first, WalkState.CANCELLED);

        assertEquals(WalkState.FAILED.wireName(), statusAs("A").state());
        assertEquals(B_X, statusAs("A").targetX());
    }

    @Test
    void aTimedOutWorkerCannotReviseItsOwnCancellation() {
        // The shape a stopping script hits when its executor is parked longer
        // than the cancel is willing to wait: the cancel gives up, records
        // "cancelled" and hands the lease back, and the executor reports its own
        // terminal status afterwards — carrying the same lease, so the same
        // sequence. Ranking by sequence alone does not separate these two.
        WalkLease lease = acquireAs("A", A_X, A_Y);
        runAs("A", () -> api.walkCancel());
        assertEquals(WalkState.CANCELLED.wireName(), statusAs("A").state());

        api.releaseWalkLease(lease, WalkState.ARRIVED);

        assertEquals(WalkState.CANCELLED.wireName(), statusAs("A").state(),
                "a walk that was cancelled must not read back as arrived");
    }

    @Test
    void aStatusPollNeverFallsIntoTheGapBetweenReleaseAndRecord() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean sawHole = new AtomicBoolean();
        AtomicReference<String> probed = new AtomicReference<>();
        // Remembers *which* owner was seen walking, not merely that one was. A
        // plain flag races: the poller's status read and its write are not one
        // step, so an observation of round n can land after round n+1 has
        // begun and be read as a hole in the wrong round.
        AtomicReference<String> walkingSeenFor = new AtomicReference<>();

        Thread poller = new Thread(() -> {
            while (!stop.get()) {
                String owner = probed.get();
                if (owner == null) {
                    Thread.onSpinWait();
                    continue;
                }
                gate.enter(owner);
                String state = api.getWalkStatus().state();
                if (WalkState.WALKING.wireName().equals(state)) {
                    walkingSeenFor.set(owner);
                } else if (WalkState.IDLE.wireName().equals(state) && owner.equals(walkingSeenFor.get())) {
                    sawHole.set(true);
                }
            }
        }, "walk-status-poller");
        poller.start();

        // A fresh owner each round: "idle" is only reachable for an owner with
        // no record at all, so idle-after-walking can only mean the poll landed
        // between the lease being withdrawn and its outcome being written.
        for (int round = 0; round < STATUS_PROBE_ROUNDS && !sawHole.get(); round++) {
            String owner = "probe-" + round;
            probed.set(owner);
            api.releaseWalkLease(api.acquireWalkLease(owner, A_X, A_Y), WalkState.ARRIVED);
        }
        stop.set(true);
        poller.join(TEST_TIMEOUT_MS);

        assertFalse(sawHole.get(),
                "a status poll saw neither an active walk nor its outcome");
    }

    @Test
    void closingTheWalkerCancelsTheActiveWalkAndForgetsOutcomes() {
        WalkLease lease = acquireAs("A", A_X, A_Y);

        api.closeWorldWalker();

        assertTrue(lease.cancel().get(), "teardown must stop whatever was walking");
        assertEquals(WalkState.IDLE.wireName(), statusAs("A").state());
        assertNotNull(acquireAs("B", B_X, B_Y), "a reconnect starts from a clean slate");
    }

    // ============================== Contention ==============================

    @Test
    void exactlyOneOfManyContendingScriptsGetsTheLease() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
        AtomicInteger winnersThisRound = new AtomicInteger();
        AtomicReference<WalkLease> winnerThisRound = new AtomicReference<>();
        AtomicInteger mostWinners = new AtomicInteger();
        AtomicInteger fewestWinners = new AtomicInteger(Integer.MAX_VALUE);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>(CONTENDERS);

        for (int i = 0; i < CONTENDERS; i++) {
            String script = "S" + i;
            boolean tallies = i == 0;
            threads.add(new Thread(() -> {
                gate.enter(script);
                try {
                    for (int round = 0; round < CONTENDED_ROUNDS; round++) {
                        startLine.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        // Nothing may sit between the barrier and the acquire.
                        // Given even a little head start the first contender has
                        // already installed its lease, every other thread takes
                        // the ordinary refusal path, and the compare-and-set that
                        // decides a genuine tie is never exercised at all.
                        WalkLease got = api.acquireWalkLease(api.callerOwner(), A_X, A_Y);
                        if (got != null) {
                            winnersThisRound.incrementAndGet();
                            winnerThisRound.set(got);
                        }
                        startLine.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (tallies) {
                            int won = winnersThisRound.getAndSet(0);
                            mostWinners.accumulateAndGet(won, Math::max);
                            fewestWinners.accumulateAndGet(won, Math::min);
                            WalkLease held = winnerThisRound.getAndSet(null);
                            if (held != null) {
                                api.releaseWalkLease(held, WalkState.ARRIVED);
                            }
                        }
                        startLine.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "contender-" + script));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join(TEST_TIMEOUT_MS);
        }

        assertNull(failure.get(), () -> "a contender threw: " + failure.get());
        assertEquals(1, mostWinners.get(), "two scripts must never both hold the character");
        assertEquals(1, fewestWinners.get(), "somebody must always get through");
    }

    // ============================== Regression guard ==============================

    @Test
    void withoutAGateEveryCallerOwnsEveryWalk() {
        GameAPIImpl ungated = new GameAPIImpl(mock(RpcClient.class));

        WalkLease first = ungated.acquireWalkLease(ungated.callerOwner(), A_X, A_Y);
        WalkLease second = ungated.acquireWalkLease(ungated.callerOwner(), B_X, B_Y);

        assertNotNull(first);
        assertNotNull(second, "existing fixtures build the impl with no gate and expect no refusals");
    }

    // ============================== Helpers ==============================

    private WalkLease acquireAs(String script, int x, int y) {
        return callAs(script, () -> api.acquireWalkLease(api.callerOwner(), x, y));
    }

    private WalkLease acquireAsHost() {
        return callAsHost(() -> api.acquireWalkLease(api.callerOwner(), A_X, A_Y));
    }

    private WalkStatus statusAs(String script) {
        return callAs(script, () -> api.getWalkStatus());
    }

    private long refusalCountAs(String script) {
        return callAs(script, () -> api.walkRefusalCount());
    }

    private long refusalCountAsHost() {
        return callAsHost(() -> api.walkRefusalCount());
    }

    private void runAs(String script, Runnable body) {
        callAs(script, () -> {
            body.run();
            return null;
        });
    }

    private void runAsHost(Runnable body) {
        callAsHost(() -> {
            body.run();
            return null;
        });
    }

    private <T> T callAs(String script, Supplier<T> body) {
        return onFreshThread(() -> {
            gate.enter(script);
            return body.get();
        });
    }

    /** A host caller is simply a thread the gate never tagged. */
    private <T> T callAsHost(Supplier<T> body) {
        return onFreshThread(body);
    }

    /**
     * Runs {@code body} on its own thread, because the owner tag is a
     * thread-local: two "scripts" sharing one thread would share one identity
     * and the contention this test is about could not arise.
     */
    private <T> T onFreshThread(Supplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                result.set(body.get());
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "walk-lease-test");
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the test thread", e);
        }
        Throwable error = thrown.get();
        if (error != null) {
            throw new AssertionError("test body threw on its own thread", error);
        }
        return result.get();
    }
}
