package com.botwithus.bot.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedConstructionTest {

    private static final Duration SHORT_BUDGET = Duration.ofMillis(200);
    private static final Duration LONG_BUDGET = Duration.ofSeconds(10);
    private static final long TEST_TIMEOUT_SECONDS = 10;

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

    /** The budget counts construction only, so a caller that waited first still gets all of it. */
    @Test
    void theBudgetStartsWhenConstructionStarts() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(SHORT_BUDGET.toMillis() * 2);

        BoundedConstruction.Outcome<Object> outcome = BoundedConstruction.construct("AfterAWait", () -> {
            sleepQuietly(SHORT_BUDGET.toMillis() / 2);
            return new Object();
        }, SHORT_BUDGET);

        assertInstanceOf(BoundedConstruction.Outcome.Built.class, outcome);
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
