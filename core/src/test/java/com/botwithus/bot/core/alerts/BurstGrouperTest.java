package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurstGrouperTest {

    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(30);

    private final BurstGrouper grouper = new BurstGrouper();

    private static Alert lost(String who, Instant at) {
        return new Alert(AlertKind.CLIENT_LOST, who + " stopped responding", at);
    }

    @Test
    void add_firstAlert_opensABurstClosingAWindowLater() {
        assertEquals(Optional.of(T0.plus(WINDOW)), grouper.add(lost("A", T0), T0, WINDOW));
    }

    @Test
    void add_withinTheWindow_joinsWithoutANewDeadline() {
        grouper.add(lost("A", T0), T0, WINDOW);

        assertEquals(Optional.empty(), grouper.add(lost("B", T0.plusSeconds(10)), T0.plusSeconds(10), WINDOW));
    }

    @Test
    void takeDue_beforeTheDeadline_isEmpty() {
        grouper.add(lost("A", T0), T0, WINDOW);

        assertEquals(Optional.empty(), grouper.takeDue(T0.plusSeconds(29)));
    }

    @Test
    void takeDue_atTheDeadline_returnsEveryAlertInOrder() {
        Alert a = lost("A", T0);
        Alert b = lost("B", T0.plusSeconds(5));
        Alert c = lost("C", T0.plusSeconds(29));
        grouper.add(a, T0, WINDOW);
        grouper.add(b, T0.plusSeconds(5), WINDOW);
        grouper.add(c, T0.plusSeconds(29), WINDOW);

        assertEquals(Optional.of(new Burst(List.of(a, b, c), 0)), grouper.takeDue(T0.plus(WINDOW)));
    }

    @Test
    void theWindowDoesNotSlide() {
        grouper.add(lost("A", T0), T0, WINDOW);
        grouper.add(lost("B", T0.plusSeconds(25)), T0.plusSeconds(25), WINDOW);

        assertTrue(grouper.takeDue(T0.plus(WINDOW)).isPresent(), "a later alert must not delay the first");
    }

    @Test
    void afterTakingABurst_theNextAlertOpensANewOne() {
        grouper.add(lost("A", T0), T0, WINDOW);
        grouper.takeDue(T0.plus(WINDOW));
        Instant later = T0.plusSeconds(100);

        assertEquals(Optional.of(later.plus(WINDOW)), grouper.add(lost("B", later), later, WINDOW));
        assertEquals(Optional.empty(), grouper.takeDue(T0.plusSeconds(101)));
    }

    @Test
    void takeDue_whenIdle_isEmpty() {
        assertEquals(Optional.empty(), grouper.takeDue(T0));
    }

    @Test
    void aFullBurst_countsTheRestAsOverflow() {
        for (int i = 0; i < BurstGrouper.MAX_PENDING + 3; i++) {
            grouper.add(lost("C" + i, T0), T0, WINDOW);
        }

        Burst burst = grouper.takeDue(T0.plus(WINDOW)).orElseThrow();

        assertEquals(BurstGrouper.MAX_PENDING, burst.alerts().size());
        assertEquals(3, burst.overflow());
        assertEquals(BurstGrouper.MAX_PENDING + 3, burst.total());
    }
}
