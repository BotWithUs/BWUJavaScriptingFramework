package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The management kind resolves from the management runtime, with no client connected. */
class LiveInspectorSourceTest {

    private static final InspectorSubject.ManagementScript BREAKS =
            new InspectorSubject.ManagementScript("Break Scheduler");

    private final ManagementScriptRuntime runtime = new ManagementScriptRuntime(null);
    private final LiveInspectorSource source = new LiveInspectorSource(List::of, () -> runtime);

    @Test
    void managementScript_resolvesToItsRunnersFieldsAndConfig() {
        ManagementScriptRunner runner = runtime.registerScript(new TestScripts.BreakScheduler());

        InspectorTarget target = source.resolve(BREAKS).orElseThrow();

        assertAll(
                () -> assertEquals(BREAKS, target.subject()),
                () -> assertEquals(LiveInspectorSource.MANAGEMENT_CONTEXT, target.context()),
                () -> assertEquals("Break Scheduler", target.script().name()),
                () -> assertEquals("1.2", target.script().version()),
                () -> assertEquals(runner.getConfigFields(), target.fields()),
                () -> assertEquals(target.fields().size(), target.script().settingsCount()),
                () -> assertFalse(target.hasCustomUi()),
                () -> assertNull(target.current().get(), "nothing applied yet"),
                () -> assertFalse(target.isGone().getAsBoolean()));
    }

    @Test
    void managementScriptWithAUi_offersTheScriptUiTab() {
        runtime.registerScript(new TestScripts.FleetMonitor());

        InspectorTarget target = source.resolve(new InspectorSubject.ManagementScript("Fleet Monitor")).orElseThrow();

        assertAll(
                () -> assertTrue(target.hasCustomUi()),
                () -> assertTrue(target.fields().isEmpty()));
    }

    @Test
    void aDisposedManagementRunner_isGone() {
        ManagementScriptRunner runner = runtime.registerScript(new TestScripts.BreakScheduler());
        InspectorTarget target = source.resolve(BREAKS).orElseThrow();

        runner.dispose();

        assertTrue(target.isGone().getAsBoolean());
    }

    @Test
    void unknownScripts_andClientScriptsWithNoClient_resolveToNothing() {
        assertAll(
                () -> assertEquals(Optional.empty(), source.resolve(BREAKS)),
                () -> assertEquals(Optional.empty(),
                        source.resolve(new ClientScript(TestScripts.PIPE, "Woodcutting"))),
                () -> assertEquals(Optional.empty(),
                        new LiveInspectorSource(List::of, () -> null).resolve(BREAKS),
                        "no management runtime yet"));
    }

    @Test
    void itemNames_withNoClientConnected_areUnknown() {
        runtime.registerScript(new TestScripts.BreakScheduler());

        assertEquals(Optional.empty(), source.resolve(BREAKS).orElseThrow().itemName().apply(995));
    }
}
