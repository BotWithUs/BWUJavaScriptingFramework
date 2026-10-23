package com.botwithus.bot.core.rpc;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectPolicyTest {

    @Test
    void defaultPolicyHasUnboundedAttempts() {
        assertEquals(ReconnectPolicy.UNLIMITED, ReconnectPolicy.DEFAULT.maxAttempts());
        assertEquals(OptionalInt.empty(), ReconnectPolicy.DEFAULT.attemptLimit());
        assertEquals(500L, ReconnectPolicy.DEFAULT.initialDelayMs());
        assertEquals(2.0, ReconnectPolicy.DEFAULT.backoffMultiplier(), 1e-9);
        assertEquals(15_000L, ReconnectPolicy.DEFAULT.maxDelayMs());
    }

    @Test
    void delayGrowsExponentiallyAndClampsToMax() {
        ReconnectPolicy p = new ReconnectPolicy(10, 100, 2.0, 1_000);
        assertEquals(0L, p.delayForAttempt(0));
        assertEquals(100L, p.delayForAttempt(1));
        assertEquals(200L, p.delayForAttempt(2));
        assertEquals(400L, p.delayForAttempt(3));
        assertEquals(800L, p.delayForAttempt(4));
        // attempt 5 -> 1600ms, clamped to 1000
        assertEquals(1_000L, p.delayForAttempt(5));
        assertEquals(1_000L, p.delayForAttempt(20));
    }

    @Test
    void zeroInitialDelayStaysZero() {
        ReconnectPolicy p = new ReconnectPolicy(5, 0, 2.0, 100);
        assertEquals(0L, p.delayForAttempt(1));
        assertEquals(0L, p.delayForAttempt(5));
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(-1, 100, 2.0, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(5, -1, 2.0, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(5, 100, 0.5, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(5, 1000, 2.0, 500));
    }

    @Test
    void delayForNegativeAttemptIsZero() {
        ReconnectPolicy p = new ReconnectPolicy(5, 100, 2.0, 1000);
        assertEquals(0L, p.delayForAttempt(-1));
    }

    @Test
    void zeroAttemptsMeansUnlimited() {
        ReconnectPolicy p = new ReconnectPolicy(ReconnectPolicy.UNLIMITED, 100, 2.0, 1000);

        assertEquals(OptionalInt.empty(), p.attemptLimit(),
                "an unlimited policy has no limit to show, not a limit of zero");
        assertTrue(p.allowsAttempt(1));
        assertTrue(p.allowsAttempt(Integer.MAX_VALUE));
    }

    @Test
    void aPositiveBudgetAllowsExactlyThatManyAttempts() {
        ReconnectPolicy p = new ReconnectPolicy(3, 100, 2.0, 1000);

        assertEquals(OptionalInt.of(3), p.attemptLimit());
        assertTrue(p.allowsAttempt(3));
        assertFalse(p.allowsAttempt(4));
    }

    @Test
    void clampedRaisesALongestWaitShorterThanTheFirstWait() {
        ReconnectPolicy p = ReconnectPolicy.clamped(5, 60_000, 2.0, 1_000);

        assertEquals(60_000L, p.initialDelayMs());
        assertEquals(60_000L, p.maxDelayMs(),
                "the longest wait cannot be shorter than the first one");
        assertEquals(60_000L, p.delayForAttempt(3));
    }

    @Test
    void clampedPullsOutOfRangeValuesIntoRange() {
        ReconnectPolicy p = ReconnectPolicy.clamped(-4, -1, 0.5, -10);

        assertEquals(ReconnectPolicy.UNLIMITED, p.maxAttempts(), "a negative budget is no budget");
        assertEquals(0L, p.initialDelayMs());
        assertEquals(1.0, p.backoffMultiplier(), 1e-9);
        assertEquals(0L, p.maxDelayMs());
    }

    @Test
    void clampedCapsABudgetTooLargeForAnInt() {
        ReconnectPolicy p = ReconnectPolicy.clamped(Long.MAX_VALUE, 0, 1.0, 0);

        assertEquals(OptionalInt.of(Integer.MAX_VALUE), p.attemptLimit());
    }

    @Test
    void clampedKeepsAValidPolicyAsItIs() {
        assertEquals(new ReconnectPolicy(7, 250, 1.5, 9_000),
                ReconnectPolicy.clamped(7, 250, 1.5, 9_000));
    }
}
