package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import org.junit.jupiter.api.Test;
import org.msgpack.value.ValueFactory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decodes every service message this host consumes from the vendored fixtures. */
class LauncherDecoderTest {

    private static final LauncherFixtures FIXTURES = LauncherFixtures.load();
    private static final String ACCOUNT = "4f1c0b8e9a7d6c5b4a3928170615f4e3";
    private static final String SHA_A = "3f1c2d4e5b6a79880112233445566778899aabbccddeeff00112233445566778";
    private static final String SHA_B = "ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12";

    @Test
    void automationHello_passesTheReplyCheck() {
        Envelope reply = FIXTURES.envelope("hello.resp.automation");
        assertAll(() -> assertTrue(reply.isOk()),
                () -> assertDoesNotThrow(() -> HelloReply.check(reply.body())));
    }

    @Test
    void fullSurfaceHello_failsTheReplyCheck() {
        Envelope reply = FIXTURES.envelope("hello.resp");
        LauncherException e = assertThrows(LauncherException.class, () -> HelloReply.check(reply.body()));
        assertEquals(HelloReply.PROTOCOL_MISMATCH, e.code());
    }

    @Test
    void automationAccounts_decodeToIdAndName() {
        assertEquals(List.of(new LauncherAccount(ACCOUNT, "Main")),
                LauncherDecoder.accounts(FIXTURES.envelope("accounts.list.resp.automation").body()));
    }

    @Test
    void automationClientStatus_decodesEveryField() {
        LaunchedClient c = LauncherDecoder.client(FIXTURES.envelope("client.status.resp.automation").body());
        LaunchedClient expected = new LaunchedClient("c42", 12345, ACCOUNT, "Main", -1, LaunchedClient.Kind.JAGEX,
                LaunchedClient.Origin.AUTOMATION,
                Optional.of(new LaunchedClient.LaunchedBy(4321, "java", Optional.of("Main profile"))),
                OptionalLong.empty(), LaunchedClient.State.QUEUED, 1790000000000L, "", false,
                new LaunchedClient.Licence(LaunchedClient.LicenceState.UNTRACKED, 0, Optional.empty()));
        assertEquals(expected, c);
    }

    @Test
    void clientList_decodesBothClients() {
        List<LaunchedClient> clients = LauncherDecoder.clients(FIXTURES.envelope("client.list.resp").body());
        assertAll(() -> assertEquals(2, clients.size()),
                () -> assertEquals(LaunchedClient.State.INJECTED, clients.get(0).state()),
                () -> assertEquals(LaunchedClient.State.INJECTING, clients.get(1).state()),
                () -> assertEquals(70001, clients.get(1).pid()),
                () -> assertEquals(LaunchedClient.LicenceState.UNTRACKED, clients.get(1).licence().state()));
    }

    @Test
    void clientStarted_carriesRestartOfAndLaunchedBy() {
        LauncherEvent event = decodeEvent("client.started.event");
        LaunchedClient client = assertClientStarted(event);
        assertAll(() -> assertEquals(OptionalLong.of(12345), client.restartOf()),
                () -> assertEquals(12346, client.pid()),
                () -> assertEquals(LaunchedClient.State.INJECTED, client.state()),
                () -> assertEquals(SHA_A, client.agentSha()));
    }

    @Test
    void clientState_progressAndFailure() {
        assertEquals(new LauncherEvent.ClientStateChanged("c7", LaunchedClient.State.SPAWNING, Optional.of("window"),
                        Optional.empty(), Optional.of("Spawned pid 12346, waiting for the game window (up to 90s)")),
                decodeEvent("client.state.event"));
        assertEquals(new LauncherEvent.ClientStateChanged("c7", LaunchedClient.State.FAILED, Optional.of("inject"),
                        Optional.of("launch_failed"), Optional.of("Inject failed: agent pipe did not open")),
                decodeEvent("client.state.event.failed"));
    }

    @Test
    void clientExited_licenceReason() {
        assertEquals(new LauncherEvent.ClientExited("c7", 88, LauncherEvent.ExitReason.LICENCE),
                decodeEvent("client.exited.event"));
    }

    @Test
    void agentAndDataEvents() {
        assertAll(() -> assertEquals(new LauncherEvent.AgentUpdated(SHA_B), decodeEvent("agent.updated.event")),
                () -> assertEquals(new LauncherEvent.DataUpdateAvailable(LauncherEvent.DataPhase.STAGED,
                        Optional.of(SHA_B), 2, true), decodeEvent("data.update_available.event")),
                () -> assertEquals(new LauncherEvent.DataUpdateApplied(SHA_B),
                        decodeEvent("data.update_applied.event")));
    }

    @Test
    void licenceEvents_perClientAndLinkOnly() {
        assertEquals(new LauncherEvent.LicenceChanged("live", Optional.of("c7"),
                        Optional.of(LaunchedClient.LicenceState.RETRYING), OptionalInt.of(2)),
                decodeEvent("licence.state.event"));
        assertEquals(new LauncherEvent.LicenceChanged("reconnecting", Optional.empty(), Optional.empty(),
                OptionalInt.empty()), decodeEvent("licence.state.event.link"));
    }

    @Test
    void serviceShuttingDown() {
        assertEquals(new LauncherEvent.ServiceShuttingDown(false), decodeEvent("service.shutting_down.event"));
    }

    @Test
    void closeRequested_isAHostRequestNotAScriptEvent() {
        Envelope event = FIXTURES.envelope("host.close_requested.event");
        assertAll(() -> assertEquals(new CloseRequest(17, "data_update", 2),
                        LauncherDecoder.closeRequest(event.body())),
                () -> assertEquals(Optional.empty(), LauncherDecoder.event(event.method(), event.body())),
                () -> assertEquals(10, event.seq()));
    }

    @Test
    void rateLimited_carriesRetryAfter() {
        Envelope response = FIXTURES.envelope("error.rate_limited");
        LauncherException e = response.error().orElseThrow().toException(response.method());
        assertAll(() -> assertEquals(LauncherException.RATE_LIMITED, e.code()),
                () -> assertTrue(e.isRetryable()),
                () -> assertEquals(OptionalLong.of(20000), e.retryAfterMs()));
    }

    @Test
    void badRequest_carriesItsField() {
        Envelope response = FIXTURES.envelope("error.bad_request");
        LauncherException e = response.error().orElseThrow().toException(response.method());
        assertAll(() -> assertEquals("bad_request", e.code()),
                () -> assertEquals(Optional.of("accountId"), e.field()),
                () -> assertEquals(OptionalLong.empty(), e.retryAfterMs()));
    }

    @Test
    void unknownEventsAndEnumStrings_areNotErrors() {
        assertEquals(Optional.empty(), LauncherDecoder.event("future.thing", ValueFactory.emptyMap()));
        assertEquals(LaunchedClient.State.UNKNOWN, LauncherDecoder.state("hibernating"));
        assertEquals(LauncherEvent.ExitReason.UNKNOWN, LauncherDecoder.exitReason("meteor"));
    }

    @Test
    void trailingBytesAndWrongEnvelopeVersion_areMalformed() {
        byte[] good = FIXTURES.named("client.list.req").bytes();
        byte[] trailing = new byte[good.length + 1];
        System.arraycopy(good, 0, trailing, 0, good.length);
        assertThrows(MalformedFrameException.class, () -> Envelope.decode(trailing));
        byte[] v2 = LauncherRequests.pack(ValueFactory.newMapBuilder()
                .put(ValueFactory.newString("v"), ValueFactory.newInteger(2)).build());
        assertThrows(MalformedFrameException.class, () -> Envelope.decode(v2));
    }

    private static LauncherEvent decodeEvent(String fixture) {
        Envelope e = FIXTURES.envelope(fixture);
        assertEquals(Envelope.Kind.EVENT, e.kind(), fixture);
        return LauncherDecoder.event(e.method(), e.body()).orElseThrow();
    }

    private static LaunchedClient assertClientStarted(LauncherEvent event) {
        return switch (event) {
            case LauncherEvent.ClientStarted started -> started.client();
            default -> throw new AssertionError("expected ClientStarted, got " + event);
        };
    }
}
