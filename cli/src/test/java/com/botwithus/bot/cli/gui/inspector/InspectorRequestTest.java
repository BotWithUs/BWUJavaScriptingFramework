package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The retired floating windows picked what to show from the runner: its fields
 * when it had any, else its own UI. The inspector keeps that choice as the tab
 * it opens on, and the kind decides the page.
 */
class InspectorRequestTest {

    @Test
    void clientScriptWithFields_opensOnSettings_onItsClient() {
        InspectorRequest request = InspectorRequest.forClientScript(
                TestScripts.clientRunner(new TestScripts.Woodcutting()));

        assertAll(
                () -> assertEquals(new ClientScript(TestScripts.PIPE, "Woodcutting"), request.subject()),
                () -> assertEquals(InspectorTab.SETTINGS, request.tab()),
                () -> assertEquals(PageId.CLIENTS, request.subject().ownerPage()));
    }

    @Test
    void clientScriptWithOnlyAUi_opensOnScriptUi() {
        InspectorRequest request = InspectorRequest.forClientScript(
                TestScripts.clientRunner(new TestScripts.UiOnly()));

        assertEquals(InspectorTab.SCRIPT_UI, request.tab());
    }

    @Test
    void aRunnerWithNoConnection_namesNoClient_ratherThanThrowing() {
        ScriptRunner runner = new ScriptRunner(new TestScripts.Woodcutting(), null);

        assertEquals(new ClientScript("", "Woodcutting"), InspectorRequest.forClientScript(runner).subject());
    }

    @Test
    void managementScript_isTheManagementKind_onTheManagementPage() {
        InspectorRequest request = InspectorRequest.forManagementScript(
                TestScripts.managementRunner(new TestScripts.BreakScheduler()));

        assertAll(
                () -> assertEquals(new InspectorSubject.ManagementScript("Break Scheduler"), request.subject()),
                () -> assertEquals(InspectorTab.SETTINGS, request.tab()),
                () -> assertEquals(PageId.MANAGEMENT, request.subject().ownerPage()));
    }

    @Test
    void aManagementTarget_opensOnThatTargetsSettings() {
        Target woodcutting = new Target.ClientScript("3f9a1c2e58b04d7a9e216c0f4b7d2a18", "Woodcutting");

        InspectorRequest request = InspectorRequest.forManagementTarget(
                TestScripts.managementRunner(new TestScripts.FleetMonitor()), woodcutting);

        assertAll(
                () -> assertEquals(new InspectorSubject.ManagementScript("Fleet Monitor", Optional.of(woodcutting)),
                        request.subject()),
                () -> assertEquals(InspectorTab.SETTINGS, request.tab(), "settings, even for a UI-only script"));
    }

    @Test
    void managementScriptWithOnlyAUi_opensOnScriptUi() {
        InspectorRequest request = InspectorRequest.forManagementScript(
                TestScripts.managementRunner(new TestScripts.FleetMonitor()));

        assertEquals(InspectorTab.SCRIPT_UI, request.tab());
    }

    @Test
    void aScriptWhoseFieldsThrow_opensOnSettings_whichSaysItHasNone() {
        InspectorRequest request = InspectorRequest.forManagementScript(
                TestScripts.managementRunner(new TestScripts.Broken()));

        assertEquals(InspectorTab.SETTINGS, request.tab());
    }

    @Test
    void initialTab_fieldsWin_uiOnlyOpensTheUi_neitherOpensSettings() {
        assertAll(
                () -> assertEquals(InspectorTab.SETTINGS, InspectorTab.initialFor(true, true)),
                () -> assertEquals(InspectorTab.SETTINGS, InspectorTab.initialFor(true, false)),
                () -> assertEquals(InspectorTab.SCRIPT_UI, InspectorTab.initialFor(false, true)),
                () -> assertEquals(InspectorTab.SETTINGS, InspectorTab.initialFor(false, false)));
    }
}
