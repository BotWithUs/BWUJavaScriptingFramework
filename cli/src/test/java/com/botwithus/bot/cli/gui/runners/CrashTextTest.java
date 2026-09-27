package com.botwithus.bot.cli.gui.runners;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrashTextTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void summary_namesTheExceptionAndTheMethodItWasThrownIn() {
        LastCrash crash = new LastCrash(Phase.ON_START, 0L, T0, new IllegalArgumentException("bad"));

        assertEquals("IllegalArgumentException in onStart()", CrashText.summary(crash));
    }

    @Test
    void summary_withNoException_callsItAnError() {
        LastCrash crash = new LastCrash(Phase.ON_STOP, 0L, T0, null);

        assertEquals("Error in onStop()", CrashText.summary(crash));
    }

    @Test
    void method_namesEachPhaseAsAScriptAuthorWritesIt() {
        List<String> methods = Stream.of(Phase.values()).map(CrashText::method).toList();

        assertEquals(List.of("onStart()", "onLoop()", "onStop()", "onConfigUpdate()"), methods);
    }
}
