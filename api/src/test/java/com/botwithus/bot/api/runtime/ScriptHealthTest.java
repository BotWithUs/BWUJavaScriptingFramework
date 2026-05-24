package com.botwithus.bot.api.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ScriptHealthTest {

    @Test
    void healthySentinelIsEmpty() {
        assertEquals(0L, ScriptHealth.HEALTHY.totalCrashes());
        assertTrue(ScriptHealth.HEALTHY.lastCrash().isEmpty());
    }

    @Test
    void withCrashRecordsAndIncrements() {
        LastCrash crash = new LastCrash(Phase.ON_LOOP, 5L, Instant.now(),
                new RuntimeException("boom"));
        ScriptHealth h = ScriptHealth.HEALTHY.withCrash(crash);
        assertEquals(1L, h.totalCrashes());
        assertTrue(h.lastCrash().isPresent());
        assertSame(crash, h.lastCrash().get());
    }

    @Test
    void withCrashIsImmutable() {
        LastCrash crash = new LastCrash(Phase.ON_START, 0L, Instant.now(),
                new RuntimeException("first"));
        ScriptHealth h1 = ScriptHealth.HEALTHY.withCrash(crash);
        ScriptHealth h2 = h1.withCrash(new LastCrash(Phase.ON_LOOP, 1L, Instant.now(),
                new RuntimeException("second")));
        // h1 should be unchanged
        assertEquals(1L, h1.totalCrashes());
        assertEquals(2L, h2.totalCrashes());
        assertEquals(Phase.ON_LOOP, h2.lastCrash().orElseThrow().phase());
    }

    @Test
    void lastCrashStackTraceContainsThrowable() {
        Throwable t = new IllegalStateException("traceable");
        LastCrash crash = new LastCrash(Phase.ON_CONFIG_UPDATE, 0L, Instant.now(), t);
        String trace = crash.stackTrace();
        assertTrue(trace.contains("IllegalStateException"));
        assertTrue(trace.contains("traceable"));
    }
}
