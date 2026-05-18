package com.botwithus.bot.core.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReconnectPolicyTest {

    @Test
    void defaultPolicyHasUnboundedAttempts() {
        assertEquals(Integer.MAX_VALUE, ReconnectPolicy.DEFAULT.maxAttempts());
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
}
