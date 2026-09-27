package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AlertClassifierTest {

    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");
    private static final String PIPE = "BotWithUs_4312";
    private static final ClientRef CLIENT = new ClientRef(PIPE);
    private static final String UUID = "3f2b9c1e-6a4d-4c1b-9e8f-1a2b3c4d5e6f";
    private static final LastCrash NPE = new LastCrash(Phase.ON_LOOP, 12, T0,
            new NullPointerException("C:\\Users\\bob\\secret-path was null"));

    private final FakeClientDirectory directory = new FakeClientDirectory().name(PIPE, "Hollowmere");
    private final AlertClassifier classifier = new AlertClassifier(directory);

    private Optional<Alert> classify(HostEvent event) {
        return classifier.classify(event);
    }

    @Test
    void connectionLost_isClientStopsResponding() {
        assertEquals(Optional.of(new Alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding", T0)),
                classify(new HostEvent.ConnectionLost(CLIENT, new IOException("pipe broken"), T0)));
    }

    @Test
    void unknownName_fallsBackToThePipe() {
        directory.forget(PIPE);

        assertEquals("BotWithUs_4312 stopped responding",
                classify(new HostEvent.ConnectionLost(CLIENT, null, T0)).orElseThrow().headline());
    }

    @Test
    void reconnected_isClientComesBack() {
        assertEquals(Optional.of(new Alert(AlertKind.CLIENT_BACK, "Hollowmere is back", T0)),
                classify(new HostEvent.ReconnectStateChanged(CLIENT, new ReconnectState.Connected(0), T0)));
    }

    @Test
    void reconnecting_isNotAnAlert() {
        assertEquals(Optional.empty(), classify(new HostEvent.ReconnectStateChanged(CLIENT,
                new ReconnectState.Reconnecting(0, 2, 1000), T0)));
    }

    @Test
    void givingUp_afterTheGameExited_isClientClosed() {
        directory.exited(PIPE);

        assertEquals(Optional.of(new Alert(AlertKind.CLIENT_CLOSED, "Hollowmere closed", T0)),
                classify(new HostEvent.ReconnectStateChanged(CLIENT,
                        new ReconnectState.GivingUp(0, 3, new IOException()), T0)));
    }

    @Test
    void givingUp_whileTheGameRuns_isNotAnAlert() {
        assertEquals(Optional.empty(), classify(new HostEvent.ReconnectStateChanged(CLIENT,
                new ReconnectState.GivingUp(0, 3, new IOException()), T0)));
    }

    @Test
    void deadConnectionRemoved_isClientClosed_onlyOnce() {
        directory.exited(PIPE);
        classify(new HostEvent.ReconnectStateChanged(CLIENT, new ReconnectState.GivingUp(0, 3, null), T0));

        assertEquals(Optional.empty(), classify(
                new HostEvent.ClientClosed(CLIENT, HostEvent.CloseCause.CONNECTION_LOST, T0)),
                "already reported when the reconnect gave up");
    }

    @Test
    void deadConnectionRemoved_withoutAnEarlierClose_isClientClosed() {
        assertEquals(AlertKind.CLIENT_CLOSED, classify(
                new HostEvent.ClientClosed(CLIENT, HostEvent.CloseCause.CONNECTION_LOST, T0)).orElseThrow().kind());
    }

    @Test
    void userDisconnect_isNotAnAlert() {
        assertEquals(Optional.empty(),
                classify(new HostEvent.ClientClosed(CLIENT, HostEvent.CloseCause.DISCONNECTED, T0)));
    }

    @Test
    void scriptCrash_namesScriptClientExceptionAndHook_butNotTheMessage() {
        Alert alert = classify(new HostEvent.ScriptCrashed(CLIENT, "Fishing", NPE, T0)).orElseThrow();

        assertAll(
                () -> assertEquals(AlertKind.SCRIPT_CRASH, alert.kind()),
                () -> assertEquals("Fishing crashed on Hollowmere", alert.headline()),
                () -> assertEquals("NullPointerException in onLoop()", alert.detail()),
                () -> assertFalse(alert.detail().contains("Users")));
    }

    @Test
    void scriptStall_isScriptStalls() {
        assertEquals(Optional.of(new Alert(AlertKind.SCRIPT_STALL, "Fishing stalled on Hollowmere", T0)),
                classify(new HostEvent.ScriptStalled(CLIENT, "Fishing", T0)));
    }

    @Test
    void loadFailure_namesTheFileOnly() {
        Alert alert = classify(new HostEvent.ScriptLoadFailed(
                Path.of("C:", "Users", "bob", "scripts", "fishing.jar"),
                new IllegalStateException("bad module C:\\Users\\bob"), T0)).orElseThrow();

        assertAll(
                () -> assertEquals(AlertKind.JAR_LOAD_FAILED, alert.kind()),
                () -> assertEquals("fishing.jar failed to load", alert.headline()),
                () -> assertEquals("IllegalStateException", alert.detail()));
    }

    @Test
    void managementAction_isManagementScriptActs() {
        assertEquals(Optional.of(new Alert(AlertKind.MANAGEMENT_ACTION,
                        "Breaks · start break · Hollowmere", "ok", T0)),
                classify(new HostEvent.ManagementAction("Breaks", "start break", "Hollowmere", "ok", T0)));
    }

    @Test
    void managementScriptCrash_isAScriptCrash() {
        Alert alert = classify(new HostEvent.ManagementScriptCrashed("Breaks", NPE, T0)).orElseThrow();

        assertEquals(AlertKind.SCRIPT_CRASH, alert.kind());
        assertEquals("Management script Breaks crashed", alert.headline());
    }

    @Test
    void lifecycleNoise_isNotAnAlert() {
        assertAll(
                () -> assertEquals(Optional.empty(), classify(new HostEvent.ClientOpened(CLIENT, T0))),
                () -> assertEquals(Optional.empty(), classify(new HostEvent.ScriptStarted(CLIENT, "F", T0))),
                () -> assertEquals(Optional.empty(), classify(new HostEvent.ScriptStopped(CLIENT, "F", T0))),
                () -> assertEquals(Optional.empty(), classify(new HostEvent.ClientForgotten(CLIENT, T0))));
    }

    @Test
    void nameIsRemembered_afterTheConnectionIsGone() {
        classify(new HostEvent.ConnectionLost(CLIENT, null, T0));
        directory.forget(PIPE);

        assertEquals("Hollowmere closed", classify(
                new HostEvent.ClientClosed(CLIENT, HostEvent.CloseCause.CONNECTION_LOST, T0)).orElseThrow().headline());
        assertEquals("Hollowmere", classifier.nameOf(CLIENT));
    }

    @Test
    void forgettingAClient_dropsItsName() {
        classify(new HostEvent.ConnectionLost(CLIENT, null, T0));
        directory.forget(PIPE);
        classify(new HostEvent.ClientForgotten(CLIENT, T0));

        assertEquals(PIPE, classifier.nameOf(CLIENT));
    }

    @Test
    void resumedOnANewPipe_isClientComesBack_namedFromItsIdentification() {
        ClientRef account = new ClientRef(ClientKey.account(UUID), "BotWithUs_9001");
        classify(new HostEvent.ClientIdentified(account, Optional.of("Ravenmoor"), T0));

        assertEquals(Optional.of(new Alert(AlertKind.CLIENT_BACK, "Ravenmoor is back", T0)),
                classify(new HostEvent.ClientResumed(account, Optional.of(PIPE), T0)));
    }

    @Test
    void aClientWithNoNameAndNoPipe_isNeverNamedByItsAccountId() {
        ClientRef remembered = new ClientRef(ClientKey.account(UUID), ClientRef.NO_PIPE);

        String headline = classify(new HostEvent.ClientClosed(remembered,
                HostEvent.CloseCause.CONNECTION_LOST, T0)).orElseThrow().headline();

        assertEquals("A client closed", headline);
        assertFalse(headline.contains(UUID));
    }

    @Test
    void aReopenedClient_canBeReportedClosedAgain() {
        directory.exited(PIPE);
        classify(new HostEvent.ReconnectStateChanged(CLIENT, new ReconnectState.GivingUp(0, 1, null), T0));
        classify(new HostEvent.ClientOpened(CLIENT, T0));

        assertEquals(AlertKind.CLIENT_CLOSED, classify(new HostEvent.ReconnectStateChanged(CLIENT,
                new ReconnectState.GivingUp(0, 1, null), T0)).orElseThrow().kind());
    }
}
