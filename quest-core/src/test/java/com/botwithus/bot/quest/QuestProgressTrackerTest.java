package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.event.VarChangeEvent;
import com.botwithus.bot.api.event.VarbitChangeEvent;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.test.InMemoryEventBus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestProgressTrackerTest {

    private static final QuestId QUEST = new QuestId(257, "Cook's Assistant", new int[]{ 2492 });

    @Test
    void seedsCacheFromBatchVarpRpcOnConstruction() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(List.of(2492))).thenReturn(List.of(1));
        when(api.queryVarbits(List.of(2492))).thenReturn(List.of(new VarbitValue(2492, -1)));

        QuestProgressTracker tracker = new QuestProgressTracker(QUEST, api, bus);

        assertEquals(1, tracker.peek().get(2492));
        verify(api, atLeast(1)).getVarps(anyList());
        tracker.close();
    }

    @Test
    void incrementalVarChangeEventUpdatesCache() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(0));
        when(api.queryVarbits(anyList())).thenReturn(List.of(new VarbitValue(2492, -1)));

        QuestProgressTracker tracker = new QuestProgressTracker(QUEST, api, bus);
        assertEquals(0, tracker.peek().get(2492));

        bus.publish(new VarChangeEvent(2492, 0, 1));
        assertEquals(1, tracker.peek().get(2492),
                "VarChangeEvent for a tracked id must land in the cache without an RPC");

        tracker.close();
    }

    @Test
    void varbitChangeEventForUntrackedIdIsIgnored() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(0));
        when(api.queryVarbits(anyList())).thenReturn(List.of(new VarbitValue(2492, -1)));

        QuestProgressTracker tracker = new QuestProgressTracker(QUEST, api, bus);
        bus.publish(new VarbitChangeEvent(9999, 0, 5));

        assertEquals(0, tracker.peek().get(2492));
        assertEquals(0, tracker.peek().get(9999),
                "untracked id should remain at the default 0");
        tracker.close();
    }

    @Test
    void closeUnsubscribesSoLaterEventsAreNotApplied() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(0));
        when(api.queryVarbits(anyList())).thenReturn(List.of(new VarbitValue(2492, -1)));

        QuestProgressTracker tracker = new QuestProgressTracker(QUEST, api, bus);
        tracker.close();

        bus.publish(new VarChangeEvent(2492, 0, 7));
        assertEquals(0, tracker.peek().get(2492));
    }
}
