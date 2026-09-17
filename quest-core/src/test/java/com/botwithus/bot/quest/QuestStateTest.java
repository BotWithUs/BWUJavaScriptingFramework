package com.botwithus.bot.quest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestStateTest {

    @Test
    void unsetVarReadsAsZero() {
        QuestState state = new QuestState(Map.of(1, 5));
        assertEquals(0, state.get(2));
        assertTrue(state.has(2, 0));
    }

    @Test
    void hasMatchesExactValue() {
        QuestState state = new QuestState(Map.of(297, 2, 298, 1));
        assertTrue(state.has(297, 2));
        assertFalse(state.has(297, 1));
        assertTrue(state.has(298, 1));
    }

    @Test
    void inRangeIsInclusiveOnBothEnds() {
        QuestState state = new QuestState(Map.of(2492, 1));
        assertTrue(state.inRange(2492, 0, 2));
        assertTrue(state.inRange(2492, 1, 1));
        assertFalse(state.inRange(2492, 2, 5));
    }

    @Test
    void valuesMapIsImmutableSnapshot() {
        Map<Integer, Integer> source = new java.util.HashMap<>(Map.of(1, 1));
        QuestState state = new QuestState(source);
        source.put(2, 99);
        // mutation of the source after construction must not bleed in
        assertEquals(0, state.get(2));
    }
}
