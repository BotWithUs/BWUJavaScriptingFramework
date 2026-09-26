package com.botwithus.bot.cli.gui.usermode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PulseLaneTest {

    private static final long MS = 1_000_000L;

    @Test
    void isSpike_isStrictlyMoreThanTwiceTheAverage() {
        assertAll(
                () -> assertFalse(PulseLane.isSpike(200.0, 100.0), "exactly 2x is not a spike"),
                () -> assertTrue(PulseLane.isSpike(200.1, 100.0), "just over 2x is a spike"),
                () -> assertFalse(PulseLane.isSpike(150.0, 100.0)),
                () -> assertFalse(PulseLane.isSpike(1000.0, 0.0), "no average, no spike"));
    }

    @Test
    void bars_flagOnlyTheSampleOverTwiceTheAverage() {
        long[] samples = {100 * MS, 100 * MS, 201 * MS, 199 * MS};

        List<PulseLane.Bar> bars = PulseLane.bars(samples, 100.0);

        assertAll(
                () -> assertFalse(bars.get(0).spike()),
                () -> assertFalse(bars.get(1).spike()),
                () -> assertTrue(bars.get(2).spike()),
                () -> assertFalse(bars.get(3).spike()));
    }

    @Test
    void bars_keepSampleOrderNewestInTheLastSlot() {
        long[] samples = {10 * MS, 20 * MS, 30 * MS};

        List<PulseLane.Bar> bars = PulseLane.bars(samples, 20.0);

        assertEquals(List.of(PulseLane.SLOTS - 3, PulseLane.SLOTS - 2, PulseLane.SLOTS - 1),
                bars.stream().map(PulseLane.Bar::slot).toList());
        assertTrue(bars.get(0).height() < bars.get(1).height());
        assertTrue(bars.get(1).height() < bars.get(2).height());
    }

    @Test
    void bars_useOnlyTheNewestTwentyFour() {
        long[] samples = new long[PulseLane.SLOTS + 5];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (i + 1) * MS;
        }

        List<PulseLane.Bar> bars = PulseLane.bars(samples, 1.0);

        assertEquals(PulseLane.SLOTS, bars.size());
        assertEquals(0, bars.get(0).slot());
        assertTrue(bars.get(0).older());
        assertFalse(bars.get(PulseLane.SLOTS - 1).older());
    }

    @Test
    void bars_tallestStaysBelowTheTop() {
        List<PulseLane.Bar> bars = PulseLane.bars(new long[] {5 * MS, 50 * MS}, 27.5);

        assertTrue(bars.get(1).height() < 1f);
        assertTrue(bars.get(0).height() > 0f);
    }

    @Test
    void bars_emptyHistoryDrawsNothing() {
        assertTrue(PulseLane.bars(new long[0], 0.0).isEmpty());
    }
}
