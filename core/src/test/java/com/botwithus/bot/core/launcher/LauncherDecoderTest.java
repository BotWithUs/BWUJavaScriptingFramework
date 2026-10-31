package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgValue;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void automationHello_returnsTheMethodsItLists() {
        Set<String> methods = HelloReply.check(FIXTURES.envelope("hello.resp.automation").body());
        assertAll(() -> assertEquals(10, methods.size()),
                () -> assertTrue(methods.contains(LauncherProtocol.METHOD_HOST_ACK_CLOSE)),
                () -> assertFalse(methods.contains(LauncherProtocol.METHOD_SDN_BUNDLE)));
    }

    @Test
    void sdnBundleOffer_decodesEveryField() {
        SdnBundleOffer offer = LauncherDecoder.sdnBundleOffer(FIXTURES.envelope("sdn.bundle.resp").body());
        assertAll(() -> assertEquals(1, offer.deliveryId()),
                () -> assertEquals(600_000, offer.size()),
                () -> assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                        HexFormat.of().formatHex(offer.sha256())),
                () -> assertEquals(524_288, offer.chunkSize()),
                () -> assertEquals(2, offer.chunkCount()),
                () -> assertEquals(600_000 - 524_288, offer.chunkLength(1)),
                () -> assertEquals(Optional.empty(), SdnBundleDownload.checkOffer(offer)));
    }

    @Test
    void sdnBundleChunk_decodesItsBytes() {
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", HexFormat.of().formatHex(
                LauncherDecoder.sdnBundleChunk(FIXTURES.envelope("sdn.bundle_chunk.resp").body())));
    }

    @Test
    void sdnNotDelivered_decodesToItsRetryableCode() {
        Envelope reply = FIXTURES.envelope("error.sdn_not_delivered");
        WireError error = reply.error().orElseThrow();
        assertAll(() -> assertFalse(reply.isOk()),
                () -> assertEquals(LauncherProtocol.ERROR_SDN_NOT_DELIVERED, error.code()),
                () -> assertTrue(error.isRetryable()),
                () -> assertEquals(Optional.empty(), error.field()));
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
    void nativeUpdateEvents_areNeverDataEvents() {
        for (String name : List.of("native.update_available.event", "native.update_available.event.error",
                "native.update_applied.event")) {
            Envelope event = FIXTURES.envelope(name);
            assertAll(name, () -> assertTrue(event.method().startsWith("native.")),
                    () -> assertEquals(Optional.empty(), LauncherDecoder.event(event.method(), event.body())));
        }
    }

    @Test
    void automationTopics_leaveOutNative() {
        assertAll(() -> assertFalse(LauncherProtocol.AUTOMATION_TOPICS.contains("native")),
                () -> assertTrue(LauncherProtocol.AUTOMATION_TOPICS.contains("data")));
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
        assertEquals(Optional.empty(), LauncherDecoder.event("future.thing", MsgMap.empty()));
        assertEquals(LaunchedClient.State.UNKNOWN, LauncherDecoder.state("hibernating"));
        assertEquals(LauncherEvent.ExitReason.UNKNOWN, LauncherDecoder.exitReason("meteor"));
    }

    @Test
    void trailingBytesAndWrongEnvelopeVersion_areMalformed() {
        byte[] good = FIXTURES.named("client.list.req").bytes();
        byte[] trailing = new byte[good.length + 1];
        System.arraycopy(good, 0, trailing, 0, good.length);
        assertThrows(MalformedFrameException.class, () -> Envelope.decode(trailing));
        byte[] v2 = LauncherRequests.pack(MsgMap.builder()
                .put(MsgValue.of("v"), MsgValue.of(2)).build());
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
