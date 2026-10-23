package com.botwithus.bot.cli;

import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.clients.JsonClientStore;
import com.botwithus.bot.cli.clients.RememberedClient;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.botwithus.bot.cli.FakeAgent.GET_ACCOUNT_INFO;
import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The client registry wired into the real {@link CliContext}: identification
 * through the status tracker, keying through the host bus, the history, and
 * {@code clients.json} in a temporary folder. Only the agents are fakes.
 */
class CliContextClientRegistryTest {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final String PIPE = "BotWithUs_4242";
    private static final String NEW_PIPE = "BotWithUs_5151";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final ClientKey ACCOUNT = ClientKey.account(UUID);
    private static final String NAME = "Zezima";
    private static final int IN_GAME = 30;
    private static final int WORLD = 84;
    private static final String NO_UUID = "<absent>";

    @TempDir
    Path tempDir;

    @Test
    void anIdentifiedClient_isKeyedByItsAccount_andRemembered() throws IOException {
        CliContext ctx = newContext();
        Connection conn = connect(ctx, PIPE, UUID);

        identify(ctx, conn);

        ClientRecord record = onlyClient(ctx);
        assertAll(
                () -> assertEquals(ACCOUNT, record.key()),
                () -> assertEquals(new ClientLifecycle.Connected(), record.lifecycle()),
                () -> assertEquals(Optional.of(NAME), record.name()),
                () -> assertEquals(OptionalInt.of(WORLD), record.lastWorld()),
                () -> assertEquals(ACCOUNT, ctx.clientKeyOf(PIPE)));
        assertEquals(List.of(UUID), saved().stream().map(RememberedClient::accountUuid).toList(),
                "saved as soon as it is identified");
    }

    @Test
    void identifying_movesThePipesHistoryOntoTheAccount() {
        CliContext ctx = newContext();
        Connection conn = connect(ctx, PIPE, UUID);

        identify(ctx, conn);

        List<Class<? extends HostEvent>> types = ctx.getConnectionHistory().forClient(ACCOUNT).stream()
                .<Class<? extends HostEvent>>map(HostEvent::getClass).toList();
        assertEquals(List.of(ClientOpened.class, ClientIdentified.class), types);
        assertTrue(ctx.getConnectionHistory().forClient(ClientKey.pipe(PIPE)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dev_uuid", NO_UUID})
    void aDevelopmentClient_isKeyedByItsPipe_andNeverSaved(String uuid) throws IOException {
        CliContext ctx = newContext();
        Connection conn = connect(ctx, PIPE, uuid);
        identify(ctx, conn);
        assertEquals(ClientKey.pipe(PIPE), onlyClient(ctx).key());
        assertEquals(new ClientLifecycle.Connected(), onlyClient(ctx).lifecycle());

        ctx.handleConnectionError(PIPE);
        ctx.saveClients();

        assertTrue(isClosed(onlyClient(ctx)), "kept, closed, until the host exits");
        assertEquals(List.of(), saved(), "a pipe-keyed client must never be written");
    }

    @Test
    void afterAHostRestart_theAccountOnANewPipe_resumesItsCard() throws IOException {
        CliContext first = newContext();
        identify(first, connect(first, PIPE, UUID));
        first.handleConnectionError(PIPE);
        first.saveClients();

        CliContext second = newContext();
        second.loadClients();
        assertTrue(isClosed(onlyClient(second)), "remembered from the first run, closed");
        assertEquals(Optional.of(NAME), onlyClient(second).name());
        assertEquals(OptionalInt.of(WORLD), onlyClient(second).lastWorld());
        Connection restarted = connect(second, NEW_PIPE, UUID);
        flush(second);
        assertEquals(2, second.getClientRegistry().clients().size(), "not merged before the account is read");

        identify(second, restarted);

        ClientRecord record = onlyClient(second);
        assertEquals(ACCOUNT, record.key());
        assertEquals(Optional.of(NEW_PIPE), record.pipe());
        assertTrue(isResuming(record), () -> "expected resuming, was " + record.lifecycle());
        assertTrue(second.getConnectionHistory().forClient(ACCOUNT).stream()
                .anyMatch(event -> event.getClass() == ClientResumed.class));
    }

    @Test
    void aGameRestartedWhileItsDeadConnectionIsStillHeld_resumesOnTheNewPipe() {
        CliContext ctx = newContext();
        Connection old = connect(ctx, PIPE, UUID);
        identify(ctx, old);
        when(old.getPipe().isOpen()).thenReturn(false);

        identify(ctx, connect(ctx, NEW_PIPE, UUID));

        ClientRecord record = onlyClient(ctx);
        assertEquals(ACCOUNT, record.key());
        assertEquals(Optional.of(NEW_PIPE), record.pipe());
        assertEquals(ClientKey.pipe(PIPE), ctx.clientKeyOf(PIPE), "the dead pipe no longer speaks for the account");
    }

    @Test
    void twoLiveClientsOnOneAccount_areBothShown() {
        CliContext ctx = newContext();
        identify(ctx, connect(ctx, PIPE, UUID));

        identify(ctx, connect(ctx, NEW_PIPE, UUID));

        assertEquals(List.of(ACCOUNT, new ClientKey.Account(UUID, 2)),
                ctx.getClientRegistry().clients().stream().map(ClientRecord::key).toList());
    }

    @Test
    void aLiveClientIsNotForgotten() {
        CliContext ctx = newContext();
        identify(ctx, connect(ctx, PIPE, UUID));

        assertEquals(ForgetResult.STILL_CONNECTED, ctx.forget(ACCOUNT));

        assertEquals(ACCOUNT, onlyClient(ctx).key());
    }

    @Test
    void forgettingARememberedClient_removesItFromClientsJson() throws IOException {
        CliContext first = newContext();
        identify(first, connect(first, PIPE, UUID));
        first.handleConnectionError(PIPE);
        first.saveClients();
        CliContext second = newContext();
        second.loadClients();

        assertEquals(ForgetResult.FORGOTTEN, second.forget(ACCOUNT));

        flush(second);
        assertTrue(second.getClientRegistry().clients().isEmpty());
        assertEquals(List.of(), saved());
    }

    @Test
    void forgettingAClosedClientByItsOldPipe_forgetsTheAccount() {
        CliContext ctx = newContext();
        identify(ctx, connect(ctx, PIPE, UUID));
        ctx.handleConnectionError(PIPE);

        assertEquals(ForgetResult.FORGOTTEN, ctx.forget(PIPE));

        flush(ctx);
        assertTrue(ctx.getClientRegistry().clients().isEmpty());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private CliContext newContext() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        return new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve("groups.json"), Runnable::run);
    }

    private static Connection connect(CliContext ctx, String pipe, String uuid) {
        Map<String, Object> info = accountInfo(NAME, NAME, uuid, IN_GAME, true);
        if (NO_UUID.equals(uuid)) {
            info.remove("account_uuid");
        }
        FakeAgent agent = new FakeAgent().reply(GET_ACCOUNT_INFO, info).world(WORLD);
        Connection conn = agent.connection(pipe);
        assertTrue(ctx.registerConnection(conn));
        return conn;
    }

    /** What the status tracker does when it reads a client: the path identification takes. */
    private static void identify(CliContext ctx, Connection conn) {
        ctx.getStatusTracker().refresh(conn);
        flush(ctx);
    }

    private static void flush(CliContext ctx) {
        assertTrue(ctx.getHostEvents().flush(FLUSH), "host events were not delivered");
    }

    private static ClientRecord onlyClient(CliContext ctx) {
        List<ClientRecord> clients = ctx.getClientRegistry().clients();
        assertEquals(1, clients.size(), () -> "expected one client, got " + clients);
        return clients.getFirst();
    }

    private static boolean isClosed(ClientRecord record) {
        return switch (record.lifecycle()) {
            case ClientLifecycle.Closed _ -> true;
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Resuming _,
                 ClientLifecycle.NotResponding _ -> false;
        };
    }

    private static boolean isResuming(ClientRecord record) {
        return switch (record.lifecycle()) {
            case ClientLifecycle.Resuming _ -> true;
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Closed _,
                 ClientLifecycle.NotResponding _ -> false;
        };
    }

    private JsonClientStore store() {
        return new JsonClientStore(tempDir.resolve(JsonClientStore.FILE_NAME));
    }

    private List<RememberedClient> saved() throws IOException {
        return store().load();
    }
}
