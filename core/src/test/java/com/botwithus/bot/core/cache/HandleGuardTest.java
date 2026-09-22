package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard's contract, proven without timing: blocking is observed through the lock's
 * own queue ({@link HandleGuard#hasQueuedCalls()}), never by sleeping and hoping.
 */
class HandleGuardTest {

    private static final long WAIT_SECONDS = 10;

    @Test
    void call_runsTheBodyWithTheLockHeld() throws Throwable {
        HandleGuard guard = new HandleGuard();

        boolean heldInside = guard.call(guard::isHeldByCurrentThread);

        assertTrue(heldInside);
        assertFalse(guard.isHeldByCurrentThread(), "released afterwards");
    }

    @Test
    void aSecondCall_waitsForTheFirstToFinish() throws Throwable {
        HandleGuard guard = new HandleGuard();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();

        Thread first = start(() -> guard.call(() -> {
            firstInside.countDown();
            return releaseFirst.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }));
        assertTrue(firstInside.await(WAIT_SECONDS, TimeUnit.SECONDS));
        Thread second = start(() -> guard.call(() -> {
            secondRan.set(true);
            return null;
        }));
        awaitQueued(guard);

        assertFalse(secondRan.get(), "the second call is parked on the lock, not running");
        releaseFirst.countDown();
        join(first, second);
        assertTrue(secondRan.get());
    }

    @Test
    void close_waitsForTheCallInFlight_thenFreesTheHandle() throws Throwable {
        HandleGuard guard = new HandleGuard();
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch callInside = new CountDownLatch(1);
        CountDownLatch releaseCall = new CountDownLatch(1);

        Thread caller = start(() -> guard.call(() -> {
            callInside.countDown();
            releaseCall.await(WAIT_SECONDS, TimeUnit.SECONDS);
            order.add("call finished");
            return null;
        }));
        assertTrue(callInside.await(WAIT_SECONDS, TimeUnit.SECONDS));
        Thread closer = start(() -> guard.close(() -> order.add("native close")));
        awaitQueued(guard);

        assertEquals(List.of(), order, "the handle is not freed under a running call");
        releaseCall.countDown();
        join(caller, closer);
        assertEquals(List.of("call finished", "native close"), order);
    }

    @Test
    void aCallAfterClose_isRefused_andNeverReachesTheHandle() throws Throwable {
        HandleGuard guard = new HandleGuard();
        guard.close(() -> null);
        AtomicBoolean ran = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> guard.call(() -> {
            ran.set(true);
            return null;
        }));
        assertFalse(ran.get());
    }

    @Test
    void close_runsTheNativeCloseOnce() throws Throwable {
        HandleGuard guard = new HandleGuard();
        AtomicInteger closes = new AtomicInteger();

        assertTrue(guard.close(closes::incrementAndGet));
        assertFalse(guard.close(closes::incrementAndGet));
        assertEquals(1, closes.get());
    }

    @Test
    void aBodyThatThrows_releasesTheLock() throws Throwable {
        HandleGuard guard = new HandleGuard();

        assertThrows(IllegalArgumentException.class, () -> guard.call(() -> {
            throw new IllegalArgumentException("boom");
        }));

        assertFalse(guard.isHeldByCurrentThread());
        assertEquals("next", guard.call(() -> "next"));
    }

    @Test
    void onEnter_runsWithTheLockHeld_onceForEveryCall() throws Throwable {
        AtomicInteger entries = new AtomicInteger();
        HandleGuard[] self = new HandleGuard[1];
        AtomicBoolean everUnlocked = new AtomicBoolean();
        self[0] = new HandleGuard(() -> {
            entries.incrementAndGet();
            everUnlocked.compareAndSet(false, !self[0].isHeldByCurrentThread());
        });

        self[0].call(() -> null);
        self[0].call(() -> null);

        assertEquals(2, entries.get());
        assertFalse(everUnlocked.get());
    }

    // ------------------------------------------------------------------ helpers

    private static Thread start(HandleGuard.NativeCall<?> body) {
        return Thread.ofPlatform().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });
    }

    /** Waits, without sleeping, until some thread is parked on the guard's lock. */
    private static void awaitQueued(HandleGuard guard) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!guard.hasQueuedCalls()) {
            assertTrue(System.nanoTime() < deadline, "no call ever queued on the lock");
            Thread.onSpinWait();
        }
    }

    private static void join(Thread... threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(t.isAlive(), t.getName() + " did not finish");
        }
    }
}
