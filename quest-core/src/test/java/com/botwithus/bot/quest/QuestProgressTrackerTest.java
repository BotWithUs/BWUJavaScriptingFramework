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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestProgressTrackerTest {

    private static final QuestId COOKS_ASSISTANT =
            new QuestId(257, "Cook's Assistant", List.of(TrackerVar.varp(2492)));

    /**
     * The Goblin Diplomacy shape: progress lives in varbits 297 and 298, and the varps with
     * those numbers are unrelated and unset. The ids are illustrative, chosen to collide.
     */
    private static final int GD_STAGE = 297;
    private static final int GD_MAIL = 298;
    private static final QuestId GOBLIN_DIPLOMACY = new QuestId(137, "Goblin Diplomacy",
            List.of(TrackerVar.varbit(GD_STAGE), TrackerVar.varbit(GD_MAIL)));

    // ------------------------------------------------------------------ regression

    /**
     * The regression the agent's "unset varp reads 0" change would have caused. The old
     * tracker read every id as a varp as well and preferred the varp whenever it was
     * non-negative, so an unset varp 297 reporting 0 hid varbit 297's real progress.
     */
    @Test
    void varbitTrackedQuest_readsItsVarbits_evenWhenTheSameNumberedVarpsReadZero() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(0, 0));
        when(api.queryVarbits(List.of(GD_STAGE, GD_MAIL))).thenReturn(
                List.of(new VarbitValue(GD_STAGE, 3), new VarbitValue(GD_MAIL, 1)));

        QuestProgressTracker tracker = new QuestProgressTracker(GOBLIN_DIPLOMACY, api, bus);

        assertEquals(3, tracker.peek().get(GD_STAGE));
        assertEquals(1, tracker.peek().get(GD_MAIL));
        verify(api, never()).getVarps(anyList());
        tracker.close();
    }

    /** The same numbers in the other id space: a varp change must not move a varbit slot. */
    @Test
    void aVarpEvent_doesNotTouchTheVarbitWithTheSameNumber() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.queryVarbits(anyList())).thenReturn(
                List.of(new VarbitValue(GD_STAGE, 3), new VarbitValue(GD_MAIL, 1)));
        QuestProgressTracker tracker = new QuestProgressTracker(GOBLIN_DIPLOMACY, api, bus);

        bus.publish(new VarChangeEvent(GD_STAGE, 0, 9));
        assertEquals(3, tracker.peek().get(GD_STAGE), "varp 297 is not varbit 297");

        bus.publish(new VarbitChangeEvent(GD_STAGE, 3, 4));
        assertEquals(4, tracker.peek().get(GD_STAGE), "varbit 297's own event still lands");
        tracker.close();
    }

    @Test
    void aVarbitEvent_doesNotTouchTheVarpWithTheSameNumber() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(1));
        QuestProgressTracker tracker = new QuestProgressTracker(COOKS_ASSISTANT, api, bus);

        bus.publish(new VarbitChangeEvent(2492, 0, 7));
        assertEquals(1, tracker.peek().get(2492), "varbit 2492 is not varp 2492");
        tracker.close();
    }

    // ------------------------------------------------------------------ seeding

    @Test
    void varpTrackedQuest_seedsFromTheVarpBatch_andNeverAsksForVarbits() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(List.of(2492))).thenReturn(List.of(1));

        QuestProgressTracker tracker = new QuestProgressTracker(COOKS_ASSISTANT, api, bus);

        assertEquals(1, tracker.peek().get(2492));
        verify(api, atLeast(1)).getVarps(anyList());
        verify(api, never()).queryVarbits(anyList());
        tracker.close();
    }

    /**
     * -1 is "not known this time": a failed read, or an older agent's unset varp. It must
     * not overwrite a value already seen, and a variable never seen stays at 0.
     */
    @Test
    void aNegativeAnswer_keepsTheLastKnownValue() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.queryVarbits(anyList()))
                .thenReturn(List.of(new VarbitValue(GD_STAGE, 3), new VarbitValue(GD_MAIL, -1)))
                .thenReturn(List.of(new VarbitValue(GD_STAGE, -1), new VarbitValue(GD_MAIL, -1)));
        QuestProgressTracker tracker = new QuestProgressTracker(GOBLIN_DIPLOMACY, api, bus);

        tracker.refresh();

        assertEquals(3, tracker.peek().get(GD_STAGE), "last known value survives a failed read");
        assertEquals(0, tracker.peek().get(GD_MAIL), "never known reads as not started");
        tracker.close();
    }

    @Test
    void aShortBatchReply_leavesTheUnansweredIdsAtTheirLastValue() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.queryVarbits(anyList())).thenReturn(List.of(new VarbitValue(GD_STAGE, 2)));

        QuestProgressTracker tracker = new QuestProgressTracker(GOBLIN_DIPLOMACY, api, bus);

        assertEquals(2, tracker.peek().get(GD_STAGE));
        assertEquals(0, tracker.peek().get(GD_MAIL));
        tracker.close();
    }

    // ------------------------------------------------------------------ carried over

    @Test
    void incrementalVarChangeEventUpdatesCache() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(0));

        QuestProgressTracker tracker = new QuestProgressTracker(COOKS_ASSISTANT, api, bus);
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

        QuestProgressTracker tracker = new QuestProgressTracker(COOKS_ASSISTANT, api, bus);
        bus.publish(new VarbitChangeEvent(9999, 0, 5));

        assertEquals(0, tracker.peek().get(2492));
        assertEquals(0, tracker.peek().get(9999), "untracked id should remain at the default 0");
        tracker.close();
    }

    @Test
    void closeUnsubscribesSoLaterEventsAreNotApplied() {
        InMemoryEventBus bus = new InMemoryEventBus();
        GameAPI api = Mockito.mock(GameAPI.class);
        when(api.getVarps(anyList())).thenReturn(List.of(0));

        QuestProgressTracker tracker = new QuestProgressTracker(COOKS_ASSISTANT, api, bus);
        tracker.close();

        bus.publish(new VarChangeEvent(2492, 0, 7));
        assertEquals(0, tracker.peek().get(2492));
    }
}
