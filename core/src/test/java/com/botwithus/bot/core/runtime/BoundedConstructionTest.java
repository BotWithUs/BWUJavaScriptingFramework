package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedConstructionTest {

    private static final Duration SHORT_BUDGET = Duration.ofMillis(200);
    private static final Duration LONG_BUDGET = Duration.ofSeconds(10);
    private static final long TEST_TIMEOUT_SECONDS = 10;
    /** How many budgets the slow constructor takes. */
    private static final int SLOW_FACTOR = 10;

    @Test
    void aConstructorThatReturns_isBuilt() {
        Object made = new Object();

        BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("Quick", () -> made, LONG_BUDGET);

        assertSame(made, assertInstanceOf(BoundedConstruction.Outcome.Built.class, outcome).instance());
    }

    /** An Error, not just an exception, comes back as an answer rather than escaping. */
    @Test
    void aConstructorThatThrowsAnError_isReportedWithIt() {
        BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("Broken", () -> {
            throw new ExceptionInInitializerError("static init failed");
        }, LONG_BUDGET);

        assertInstanceOf(ExceptionInInitializerError.class,
                assertInstanceOf(BoundedConstruction.Outcome.Threw.class, outcome).error());
    }

    /**
     * Fails if construction runs on the caller's thread: the call would block as
     * long as the constructor does, which is what left installs on "Installing".
     */
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void aConstructorThatNeverReturns_timesOutAndLeavesANamedThread() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("Stuck", () -> {
                awaitQuietly(release);
                return new Object();
            }, SHORT_BUDGET);

            assertEquals(SHORT_BUDGET,
                    assertInstanceOf(BoundedConstruction.Outcome.TimedOut.class, outcome).budget());
            assertTrue(Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().equals("sdn-construct-Stuck") && t.isDaemon()));
        } finally {
            release.countDown();
        }
    }

    /**
     * A timeout says where the construction is stuck: the stack carries the frame
     * that blocks, and the dump prints it one frame per line. Fails for an outcome
     * that knows only the budget, which left a stall with nothing but a class name
     * in the log.
     */
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void aConstructorThatNeverReturns_timesOutWithTheStackOfWhereItIsStuck() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("Blocked", () -> {
                awaitQuietly(release);
                return new Object();
            }, SHORT_BUDGET);

            BoundedConstruction.Outcome.TimedOut<?> timedOut =
                    assertInstanceOf(BoundedConstruction.Outcome.TimedOut.class, outcome);
            assertAll(
                    () -> assertTrue(timedOut.stack().stream().anyMatch(BoundedConstructionTest::isTheBlockingFrame),
                            "the stack names the frame that blocks: " + timedOut.stack()),
                    () -> assertTrue(timedOut.stack().stream()
                            .anyMatch(f -> f.getClassName().equals(CountDownLatch.class.getName())
                                    && f.getMethodName().equals("await")), "and what it blocks on"),
                    () -> assertTrue(timedOut.stackDump().lines()
                            .anyMatch(line -> line.startsWith("	at ") && line.contains(".awaitQuietly(")),
                            "the dump prints one frame per line: " + timedOut.stackDump()));
        } finally {
            release.countDown();
        }
    }

    /**
     * The budget is enforced while the construction runs. Fails for an implementation
     * that waits for the constructor and checks the time afterwards: the call would
     * take the whole sleep, not the budget.
     */
    @Test
    void theBudgetCutsTheWaitShort_ratherThanBeingCheckedAfterwards() {
        long slowMillis = SHORT_BUDGET.toMillis() * SLOW_FACTOR;
        Instant start = Instant.now();

        BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("Slow", () -> {
            sleepQuietly(slowMillis);
            return new Object();
        }, SHORT_BUDGET);

        assertInstanceOf(BoundedConstruction.Outcome.TimedOut.class, outcome);
        assertTrue(Duration.between(start, Instant.now()).toMillis() < slowMillis);
    }

    /** An interrupt is reported as such, not blamed on the script, and the flag is kept. */
    @Test
    void anInterruptedWait_isInterrupted_notAScriptFailure() {
        CountDownLatch release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("Waiting", () -> {
                awaitQuietly(release);
                return new Object();
            }, LONG_BUDGET);

            assertInstanceOf(BoundedConstruction.Outcome.Interrupted.class, outcome);
            assertTrue(Thread.interrupted(), "the interrupt flag is restored");
        } finally {
            release.countDown();
        }
    }

    private static boolean isTheBlockingFrame(StackTraceElement frame) {
        return frame.getClassName().equals(BoundedConstructionTest.class.getName())
                && frame.getMethodName().equals("awaitQuietly");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
