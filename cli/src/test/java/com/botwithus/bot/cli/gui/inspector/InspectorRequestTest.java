package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "Settings" shows is picked from the runner: a script's own UI opens in a
 * window of its own, as the floating window did before the drawer, and its
 * fields open in the drawer when it has any. The kind decides the page.
 */
class InspectorRequestTest {

    private static final ClientScript WOODCUTTING = new ClientScript(TestScripts.PIPE, "Woodcutting");

    @Test
    void clientScriptWithFields_opensOnSettings_onItsClient() {
        InspectorRequest request = InspectorRequest.forClientScript(
                TestScripts.clientRunner(new TestScripts.Woodcutting()));

        assertAll(
                () -> assertEquals(WOODCUTTING, request.subject()),
                () -> assertEquals(Optional.of(InspectorTab.SETTINGS), request.drawer()),
                () -> assertFalse(request.popsOutUi(), "no UI to pop out"),
                () -> assertEquals(PageId.CLIENTS, request.subject().ownerPage()));
    }

    @Test
    void clientScriptWithOnlyAUi_popsItOut_andLeavesTheDrawerAlone() {
        InspectorRequest request = InspectorRequest.forClientScript(
                TestScripts.clientRunner(new TestScripts.UiOnly()));

        assertAll(
                () -> assertTrue(request.popsOutUi()),
                () -> assertEquals(Optional.empty(), request.drawer(), "no near-empty drawer beside the window"));
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
                () -> assertEquals(Optional.of(InspectorTab.SETTINGS), request.drawer()),
                () -> assertEquals(PageId.MANAGEMENT, request.subject().ownerPage()));
    }

    @Test
    void aManagementTarget_opensOnThatTargetsSettings_andPopsNothingOut() {
        Target woodcutting = new Target.ClientScript("3f9a1c2e58b04d7a9e216c0f4b7d2a18", "Woodcutting");

        InspectorRequest request = InspectorRequest.forManagementTarget(
                TestScripts.managementRunner(new TestScripts.FleetMonitor()), woodcutting);

        assertAll(
                () -> assertEquals(new InspectorSubject.ManagementScript("Fleet Monitor", Optional.of(woodcutting)),
                        request.subject()),
                () -> assertEquals(Optional.of(InspectorTab.SETTINGS), request.drawer(),
                        "settings, even for a UI-only script"),
                () -> assertFalse(request.popsOutUi()));
    }

    @Test
    void managementScriptWithOnlyAUi_popsItOut() {
        InspectorRequest request = InspectorRequest.forManagementScript(
                TestScripts.managementRunner(new TestScripts.FleetMonitor()));

        assertAll(
                () -> assertTrue(request.popsOutUi()),
                () -> assertEquals(Optional.empty(), request.drawer()));
    }

    @Test
    void aScriptWhoseFieldsThrow_opensOnSettings_whichSaysItHasNone() {
        InspectorRequest request = InspectorRequest.forManagementScript(
                TestScripts.managementRunner(new TestScripts.Broken()));

        assertEquals(Optional.of(InspectorTab.SETTINGS), request.drawer());
    }

    @Test
    void settings_theUiPopsOutWheneverThereIsOne_theDrawerOpensOnFieldsOrWhenThereIsNoUi() {
        assertAll(
                () -> assertEquals(new InspectorRequest(WOODCUTTING, Optional.of(InspectorTab.SETTINGS), true),
                        InspectorRequest.settings(WOODCUTTING, true, true)),
                () -> assertEquals(new InspectorRequest(WOODCUTTING, InspectorTab.SETTINGS),
                        InspectorRequest.settings(WOODCUTTING, true, false)),
                () -> assertEquals(InspectorRequest.scriptUi(WOODCUTTING),
                        InspectorRequest.settings(WOODCUTTING, false, true)),
                () -> assertEquals(new InspectorRequest(WOODCUTTING, InspectorTab.SETTINGS),
                        InspectorRequest.settings(WOODCUTTING, false, false)));
    }

    @Test
    void aRequestThatShowsNothing_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new InspectorRequest(WOODCUTTING, Optional.empty(), false));
    }
}
