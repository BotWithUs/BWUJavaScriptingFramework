package com.botwithus.bot.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class QuestIdTest {

    @Test
    void trackerVarArrayIsDefensivelyCopiedOnConstructionAndAccess() {
        int[] source = { 297, 298 };
        QuestId id = new QuestId(137, "Goblin Diplomacy", source);
        source[0] = 0;
        assertArrayEquals(new int[]{ 297, 298 }, id.trackerVars(),
                "source mutation must not leak into the record");

        int[] read = id.trackerVars();
        read[1] = -1;
        assertArrayEquals(new int[]{ 297, 298 }, id.trackerVars(),
                "reader mutation must not leak back into the record");
        assertNotSame(read, id.trackerVars());
    }

    @Test
    void equalsHashCodeAccountForArrayContents() {
        QuestId a = new QuestId(257, "Cook's Assistant", new int[]{ 2492 });
        QuestId b = new QuestId(257, "Cook's Assistant", new int[]{ 2492 });
        QuestId c = new QuestId(257, "Cook's Assistant", new int[]{ 2493 });
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
