package com.botwithus.bot.api.diag;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubGuardTest {

    @Test
    void warnOnce_firstCall_invokesSink() {
        List<String> sink = new ArrayList<>();
        StubGuard guard = new StubGuard(sink::add);

        guard.warnOnce("queryLocations");

        assertEquals(List.of("queryLocations"), sink);
    }

    @Test
    void warnOnce_secondCallSameName_suppressed() {
        List<String> sink = new ArrayList<>();
        StubGuard guard = new StubGuard(sink::add);

        guard.warnOnce("queryLocations");
        guard.warnOnce("queryLocations");
        guard.warnOnce("queryLocations");

        assertEquals(List.of("queryLocations"), sink);
    }

    @Test
    void warnOnce_distinctNames_eachFireOnce() {
        List<String> sink = new ArrayList<>();
        StubGuard guard = new StubGuard(sink::add);

        guard.warnOnce("queryLocations");
        guard.warnOnce("queryGroundItems");
        guard.warnOnce("queryWorldMapElements");
        guard.warnOnce("queryLocations");
        guard.warnOnce("queryGroundItems");

        assertAll(
                () -> assertEquals(3, sink.size()),
                () -> assertTrue(sink.contains("queryLocations")),
                () -> assertTrue(sink.contains("queryGroundItems")),
                () -> assertTrue(sink.contains("queryWorldMapElements")));
    }

    @Test
    void warnOnce_distinctInstances_throttleIndependently() {
        List<String> sinkA = new ArrayList<>();
        List<String> sinkB = new ArrayList<>();
        StubGuard guardA = new StubGuard(sinkA::add);
        StubGuard guardB = new StubGuard(sinkB::add);

        guardA.warnOnce("queryLocations");
        guardB.warnOnce("queryLocations");
        guardA.warnOnce("queryLocations");

        assertAll(
                () -> assertEquals(List.of("queryLocations"), sinkA),
                () -> assertEquals(List.of("queryLocations"), sinkB));
    }

    @Test
    void constructor_nullSink_throws() {
        assertThrows(IllegalArgumentException.class, () -> new StubGuard(null));
    }

    @Test
    void defaultConstructor_logsViaSlf4j() {
        StubGuard guard = new StubGuard();

        guard.warnOnce("queryLocations");
        guard.warnOnce("queryLocations");
    }
}
