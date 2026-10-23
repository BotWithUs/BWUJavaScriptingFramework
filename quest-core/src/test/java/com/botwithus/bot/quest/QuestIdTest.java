package com.botwithus.bot.quest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuestIdTest {

    private static final int GOBLIN_DIPLOMACY = 137;
    private static final int COOKS_ASSISTANT = 257;

    @Test
    void trackersKeepTheirKind_andSplitByIdSpace() {
        QuestId quest = new QuestId(GOBLIN_DIPLOMACY, "Goblin Diplomacy",
                List.of(TrackerVar.varbit(297), TrackerVar.varp(2492), TrackerVar.varbit(298)));

        assertArrayEquals(new int[]{ 297, 298 }, quest.trackerVarbits());
        assertArrayEquals(new int[]{ 2492 }, quest.trackerVarps());
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedTrackerVars_isTheUnionInDeclarationOrder() {
        QuestId quest = new QuestId(GOBLIN_DIPLOMACY, "Goblin Diplomacy",
                List.of(TrackerVar.varbit(297), TrackerVar.varp(2492), TrackerVar.varbit(298)));

        assertArrayEquals(new int[]{ 297, 2492, 298 }, quest.trackerVars());
    }

    @Test
    void trackerListIsDefensivelyCopied() {
        List<TrackerVar> source = new ArrayList<>(List.of(TrackerVar.varbit(297)));
        QuestId quest = new QuestId(GOBLIN_DIPLOMACY, "Goblin Diplomacy", source);
        source.add(TrackerVar.varbit(298));

        assertEquals(List.of(TrackerVar.varbit(297)), quest.trackers(),
                "source mutation must not leak into the record");
        assertThrows(UnsupportedOperationException.class,
                () -> quest.trackers().add(TrackerVar.varp(1)));
    }

    @Test
    void aVarpAndAVarbitWithTheSameNumber_areRefused() {
        List<TrackerVar> clash = List.of(TrackerVar.varp(297), TrackerVar.varbit(297));

        assertThrows(IllegalArgumentException.class,
                () -> new QuestId(GOBLIN_DIPLOMACY, "Goblin Diplomacy", clash));
    }

    @Test
    void equalityIsByValue_andTheKindIsPartOfIt() {
        QuestId a = new QuestId(COOKS_ASSISTANT, "Cook's Assistant", List.of(TrackerVar.varp(2492)));
        QuestId b = new QuestId(COOKS_ASSISTANT, "Cook's Assistant", List.of(TrackerVar.varp(2492)));
        QuestId asVarbit = new QuestId(COOKS_ASSISTANT, "Cook's Assistant",
                List.of(TrackerVar.varbit(2492)));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, asVarbit);
    }
}
