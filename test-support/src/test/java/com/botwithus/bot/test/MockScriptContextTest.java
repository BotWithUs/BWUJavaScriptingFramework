package com.botwithus.bot.test;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.event.ActionExecutedEvent;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class MockScriptContextTest {

    private static final int SAMPLE_TILE_X = 3222;
    private static final int SAMPLE_TILE_Y = 3219;
    private static final int SAMPLE_SERVER_INDEX = 2046;
    private static final int SAMPLE_ACTION_ID = 7;
    private static final int SAMPLE_PARAM1 = 1;
    private static final int SAMPLE_PARAM2 = 2;
    private static final int SAMPLE_PARAM3 = 3;
    private static final long SAMPLE_EVENT_TIMESTAMP = 999L;

    @Test
    void defaults_emptyContext_returnsEmptySnapshotAndLiveEventBus() {
        ScriptContext ctx = MockScriptContext.builder().build();

        assertAll(
                () -> assertNotNull(ctx.getGameAPI()),
                () -> assertNotNull(ctx.getEventBus()),
                () -> assertNull(ctx.getMessageBus()),
                () -> assertNull(ctx.getSharedState()),
                () -> assertNull(ctx.getNavigation()),
                () -> assertNull(ctx.getGameAPI().getLocalPlayer()));
    }

    @Test
    void withSnapshot_propagatesToGameAPI() {
        LocalPlayer self = sampleLocalPlayer();
        GameSnapshot snapshot = CannedSnapshot.withSelf(self);
        ScriptContext ctx = MockScriptContext.builder()
                .withSnapshot(() -> snapshot)
                .build();

        GameAPI api = ctx.getGameAPI();

        assertAll(
                () -> assertSame(snapshot, api.snapshot()),
                () -> assertSame(self, api.getLocalPlayer()));
    }

    @Test
    void withSnapshot_supplierCalledOncePerSnapshotRead() {
        List<GameSnapshot> emitted = new ArrayList<>();
        emitted.add(CannedSnapshot.empty());
        emitted.add(CannedSnapshot.withSelf(sampleLocalPlayer()));
        ScriptContext ctx = MockScriptContext.builder()
                .withSnapshot(() -> emitted.remove(0))
                .build();

        GameSnapshot first = ctx.getGameAPI().snapshot();
        GameSnapshot second = ctx.getGameAPI().snapshot();

        assertAll(
                () -> assertNull(first.self()),
                () -> assertNotNull(second.self()),
                () -> assertEquals(0, emitted.size()));
    }

    @Test
    void recordingActionsInto_capturesQueueAction() {
        List<String> sink = new ArrayList<>();
        ScriptContext ctx = MockScriptContext.builder()
                .recordingActionsInto(sink)
                .build();

        ctx.getGameAPI().queueAction(new GameAction(SAMPLE_ACTION_ID, SAMPLE_PARAM1, SAMPLE_PARAM2, SAMPLE_PARAM3));

        assertEquals(List.of("7/1/2/3"), sink);
    }

    @Test
    void recordingActionsInto_capturesQueueActions() {
        List<String> sink = new ArrayList<>();
        ScriptContext ctx = MockScriptContext.builder()
                .recordingActionsInto(sink)
                .build();

        int count = ctx.getGameAPI().queueActions(List.of(
                new GameAction(1, 0, 0, 0),
                new GameAction(2, 0, 0, 0),
                new GameAction(3, 0, 0, 0)));

        assertAll(
                () -> assertEquals(3, count),
                () -> assertEquals(List.of("1/0/0/0", "2/0/0/0", "3/0/0/0"), sink));
    }

    @Test
    void eventBus_isInMemoryAndDelivers() {
        MockScriptContext ctx = MockScriptContext.builder().build();
        List<ActionExecutedEvent> received = new ArrayList<>();
        ctx.eventBus().subscribe(ActionExecutedEvent.class, received::add);

        ctx.eventBus().publish(new ActionExecutedEvent(
                SAMPLE_ACTION_ID, SAMPLE_PARAM1, SAMPLE_PARAM2, SAMPLE_PARAM3, SAMPLE_EVENT_TIMESTAMP));

        assertEquals(1, received.size());
    }

    @Test
    void unstubbedSeams_throwUnsupportedOperation() {
        ScriptContext ctx = MockScriptContext.builder().build();
        GameAPI api = ctx.getGameAPI();

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, api::ping),
                () -> assertThrows(UnsupportedOperationException.class, api::getGameCycle),
                () -> assertThrows(UnsupportedOperationException.class, api::npcs),
                () -> assertThrows(UnsupportedOperationException.class, api::backpack));
    }

    @Test
    void builder_nullSnapshotSource_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MockScriptContext.builder().withSnapshot(null));
    }

    @Test
    void builder_nullActionSink_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> MockScriptContext.builder().recordingActionsInto(null));
    }

    private static LocalPlayer sampleLocalPlayer() {
        return new LocalPlayer(
                SAMPLE_SERVER_INDEX,
                0,
                SAMPLE_TILE_X,
                SAMPLE_TILE_Y,
                0,
                0,
                -1,
                -1,
                0,
                -1,
                0,
                false,
                -1,
                List.of());
    }
}
