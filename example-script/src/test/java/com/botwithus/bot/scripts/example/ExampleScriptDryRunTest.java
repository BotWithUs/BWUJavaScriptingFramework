package com.botwithus.bot.scripts.example;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.test.CannedSnapshot;
import com.botwithus.bot.test.MockScriptContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worked example exercising {@link ExampleScript} against {@link MockScriptContext}.
 *
 * <p>Demonstrates the dry-run testing pattern from the {@code test-support}
 * module: spin up a script with a canned snapshot, drive its lifecycle, and
 * assert against what it logged / queued / read.</p>
 */
class ExampleScriptDryRunTest {

    private static final int SAMPLE_SERVER_INDEX = 2046;
    private static final int SAMPLE_TILE_X = 3222;
    private static final int SAMPLE_TILE_Y = 3219;

    @Test
    void lifecycle_completesAgainstEmptySnapshot() {
        MockScriptContext ctx = MockScriptContext.builder().build();
        ExampleScript script = new ExampleScript();

        script.onStart(ctx);
        int delay = script.onLoop();
        script.onStop();

        assertTrue(delay > 0, "onLoop should return a positive delay");
    }

    @Test
    void onLoop_readsLocalPlayerFromSnapshot() {
        LocalPlayer self = sampleLocalPlayer();
        MockScriptContext ctx = MockScriptContext.builder()
                .withSnapshot(() -> CannedSnapshot.withSelf(self))
                .build();
        ExampleScript script = new ExampleScript();

        script.onStart(ctx);
        script.onLoop();
        script.onStop();
    }

    @Test
    void getConfigFields_declaresExpectedKeys() {
        ExampleScript script = new ExampleScript();
        List<ConfigField> fields = script.getConfigFields();

        List<String> keys = fields.stream().map(ConfigField::key).toList();
        assertAll(
                () -> assertEquals(3, fields.size()),
                () -> assertTrue(keys.contains("loopDelay")),
                () -> assertTrue(keys.contains("verbose")),
                () -> assertTrue(keys.contains("mode")));
    }

    @Test
    void onLoop_queuesNoActionsByDefault() {
        List<String> sink = new ArrayList<>();
        MockScriptContext ctx = MockScriptContext.builder()
                .recordingActionsInto(sink)
                .build();
        ExampleScript script = new ExampleScript();

        script.onStart(ctx);
        script.onLoop();
        script.onStop();

        assertTrue(sink.isEmpty(), "ExampleScript should not queue any actions");
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
                List.of());
    }
}
