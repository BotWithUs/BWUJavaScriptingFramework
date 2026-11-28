package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherPlayersInViewTest {

    private static final int OWN = 7;
    private static final int OTHER = 12;
    private static final int ANOTHER = 40;
    private static final ToIntFunction<Integer> INDEX = Integer::intValue;

    @Test
    void othersOf_onlyOwnRow_returnsEmpty() {
        assertTrue(OtherPlayersInView.othersOf(List.of(OWN), INDEX, OWN).isEmpty());
    }

    @Test
    void othersOf_ownAndOthers_returnsOthersOnly() {
        assertEquals(List.of(OTHER, ANOTHER),
                OtherPlayersInView.othersOf(List.of(OTHER, OWN, ANOTHER), INDEX, OWN));
    }

    @Test
    void othersOf_emptyRead_failsRatherThanSkips() {
        assertThrows(AssertionFailedError.class, () -> OtherPlayersInView.othersOf(List.of(), INDEX, OWN));
    }

    @Test
    void othersOf_readWithoutOwnRow_failsEvenWithOtherRows() {
        assertThrows(AssertionFailedError.class,
                () -> OtherPlayersInView.othersOf(List.of(OTHER, ANOTHER), INDEX, OWN));
    }

    @Test
    void requireAny_empty_skipsNamingThePrecondition() {
        TestAbortedException skip = assertThrows(TestAbortedException.class,
                () -> OtherPlayersInView.requireAny(List.of(), "near the bank"));
        assertTrue(skip.getMessage().contains("no other player in view near the bank"), skip.getMessage());
    }

    @Test
    void requireAny_nonEmpty_returnsTheRows() {
        assertEquals(List.of(OTHER), OtherPlayersInView.requireAny(List.of(OTHER), "here"));
    }
}
