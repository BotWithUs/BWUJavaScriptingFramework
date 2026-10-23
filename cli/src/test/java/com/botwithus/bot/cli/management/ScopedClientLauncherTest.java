package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.script.LaunchHandle;
import com.botwithus.bot.api.script.LaunchOptions;
import com.botwithus.bot.api.script.LaunchOutcome;
import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.api.script.StopMode;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.core.launcher.HostClientLauncher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A script scoped to account X sees and acts on X only, and every call it
 * makes is in the audit log (launcher ADR 0007, section 10.1; Java acceptance 4).
 */
class ScopedClientLauncherTest {

    private static final String SCRIPT = "manager";
    private static final String X = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String Y = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String CLIENT_X = "c1";
    private static final String CLIENT_Y = "c2";

    private final FakeHostLauncher host = new FakeHostLauncher();
    private final OrchestratorAuditLog audit = new OrchestratorAuditLog(event -> { }, Clock.systemUTC());

    @Test
    void scopedToX_seesOnlyX() {
        ScopedClientLauncher launcher = scoped(scopeOnX());
        assertAll(() -> assertEquals(List.of(new LauncherAccount(X, "X")), launcher.accounts()),
                () -> assertEquals(List.of(CLIENT_X), launcher.clients().stream().map(LaunchedClient::clientId)
                        .toList()));
    }

    @Test
    void scopedToX_launchOnY_isRefusedAndNothingIsLaunched() {
        ScopedClientLauncher launcher = scoped(scopeOnX());
        LauncherException e = assertThrows(LauncherException.class, () -> launcher.launch(Y));
        assertAll(() -> assertEquals(LauncherException.NOT_PERMITTED, e.code()),
                () -> assertEquals(List.of(), host.launched));
    }

    @Test
    void scopedToX_stopOfYsClient_isRefusedAndNothingIsStopped() {
        ScopedClientLauncher launcher = scoped(scopeOnX());
        LauncherException e = assertThrows(LauncherException.class, () -> launcher.stop(CLIENT_Y, StopMode.KILL));
        assertAll(() -> assertEquals(LauncherException.NOT_PERMITTED, e.code()),
                () -> assertEquals(List.of(), host.stopped));
    }

    @Test
    void scopedToX_launchAndStopOnX_goThrough() {
        ScopedClientLauncher launcher = scoped(scopeOnX());
        assertEquals("c9", launcher.launch(X).clientId());
        launcher.stop(CLIENT_X, StopMode.GRACEFUL);
        assertAll(() -> assertEquals(List.of(X), host.launched),
                () -> assertEquals(List.of(CLIENT_X + ":" + StopMode.GRACEFUL), host.stopped));
    }

    @Test
    void everyCall_isAudited_refusalsIncluded() {
        ScopedClientLauncher launcher = scoped(scopeOnX());
        launcher.accounts();
        launcher.clients();
        assertThrows(LauncherException.class, () -> launcher.launch(Y));
        assertThrows(LauncherException.class, () -> launcher.stop(CLIENT_Y, StopMode.KILL));
        List<String> calls = audit.entries(SCRIPT).stream()
                .map(entry -> entry.call() + " | " + entry.target() + " | " + entry.result()).toList();
        assertEquals(List.of(
                "launcher.accounts | every account | 1 visible",
                "launcher.clients | every client | 1 visible",
                "launcher.launch | account " + Y + " | refused: " + ScopedClientOrchestrator.NOT_IN_TARGETS,
                "launcher.stop | client " + CLIENT_Y + " of account " + Y + " (KILL) | refused: "
                        + ScopedClientOrchestrator.NOT_IN_TARGETS), calls);
    }

    @Test
    void pausedOnXsGroup_mayStopButNotLaunch() {
        ClientGroup paused = ClientGroup.create("woodcutters", Optional.empty()).withMember(X)
                .withManager(Optional.of(new ManagerSlot(SCRIPT, false)));
        ScopedClientLauncher launcher = scoped(new Scope(false, List.of(paused), List.of()));
        LauncherException e = assertThrows(LauncherException.class, () -> launcher.launch(X));
        launcher.stop(CLIENT_X, StopMode.KILL);
        assertAll(() -> assertTrue(e.getMessage().contains(ScopedClientOrchestrator.PAUSED), e.getMessage()),
                () -> assertEquals(List.of(), host.launched),
                () -> assertEquals(List.of(CLIENT_X + ":" + StopMode.KILL), host.stopped));
    }

    @Test
    void wholeHost_isUnrestricted() {
        ScopedClientLauncher launcher = scoped(new Scope(true, List.of(), List.of()));
        launcher.launch(Y);
        launcher.stop(CLIENT_Y, StopMode.KILL);
        assertAll(() -> assertEquals(2, launcher.accounts().size()),
                () -> assertEquals(List.of(Y), host.launched));
    }

    @Test
    void notApplied_seesNothing() {
        ScopedClientLauncher launcher = scoped(Scope.NOT_APPLIED);
        assertAll(() -> assertEquals(List.of(), launcher.accounts()),
                () -> assertEquals(List.of(), launcher.clients()),
                () -> assertThrows(LauncherException.class, () -> launcher.launch(X)));
    }

    @Test
    void events_aboutYsClients_areHidden() {
        ScopedClientLauncher launcher = scoped(scopeOnX());
        List<LauncherEvent> seen = new CopyOnWriteArrayList<>();
        launcher.onEvent(seen::add);
        host.emit(new LauncherEvent.ClientExited(CLIENT_Y, 0, LauncherEvent.ExitReason.STOPPED));
        host.emit(new LauncherEvent.ClientExited(CLIENT_X, 0, LauncherEvent.ExitReason.STOPPED));
        host.emit(new LauncherEvent.AgentUpdated("sha"));
        assertEquals(List.of(new LauncherEvent.ClientExited(CLIENT_X, 0, LauncherEvent.ExitReason.STOPPED),
                new LauncherEvent.AgentUpdated("sha")), seen);
    }

    @Test
    void serviceFailure_isAuditedAndRethrown() {
        host.failure = Optional.of(new LauncherException(LauncherException.SERVICE_UNAVAILABLE, "down"));
        ScopedClientLauncher launcher = scoped(scopeOnX());
        LauncherException e = assertThrows(LauncherException.class, () -> launcher.launch(X));
        assertEquals(LauncherException.SERVICE_UNAVAILABLE, e.code());
        assertEquals("failed: service_unavailable", audit.entries(SCRIPT).getLast().result());
    }

    private ScopedClientLauncher scoped(Scope scope) {
        return new ScopedClientLauncher(SCRIPT, () -> host, () -> scope, audit);
    }

    private static Scope scopeOnX() {
        return new Scope(false, List.of(), List.of(new Target.ClientScript(X, "woodcutting")));
    }

    /** A host launcher with one client on each of X and Y. */
    private static final class FakeHostLauncher implements HostClientLauncher {

        private final List<String> launched = new ArrayList<>();
        private final List<String> stopped = new ArrayList<>();
        private final List<Consumer<LauncherEvent>> listeners = new CopyOnWriteArrayList<>();
        private Optional<LauncherException> failure = Optional.empty();

        @Override
        public List<LauncherAccount> accounts() {
            return List.of(new LauncherAccount(X, "X"), new LauncherAccount(Y, "Y"));
        }

        @Override
        public LaunchHandle launch(String accountId, LaunchOptions options) {
            failure.ifPresent(e -> {
                throw e;
            });
            launched.add(accountId);
            return new LaunchHandle() {
                @Override
                public String clientId() {
                    return "c9";
                }

                @Override
                public CompletionStage<LaunchOutcome> outcome() {
                    return new CompletableFuture<>();
                }
            };
        }

        @Override
        public void stop(String clientId, StopMode mode) {
            stopped.add(clientId + ":" + mode);
        }

        @Override
        public List<LaunchedClient> clients() {
            return List.of(client(CLIENT_X, X), client(CLIENT_Y, Y));
        }

        @Override
        public AutoCloseable onEvent(Consumer<LauncherEvent> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public Optional<String> accountOf(String clientId) {
            return switch (clientId) {
                case CLIENT_X -> Optional.of(X);
                case CLIENT_Y -> Optional.of(Y);
                default -> Optional.empty();
            };
        }

        void emit(LauncherEvent event) {
            listeners.forEach(l -> l.accept(event));
        }

        private static LaunchedClient client(String clientId, String account) {
            return new LaunchedClient(clientId, 1, account, account, -1, LaunchedClient.Kind.JAGEX,
                    LaunchedClient.Origin.AUTOMATION, Optional.empty(), OptionalLong.empty(),
                    LaunchedClient.State.INJECTED, 0, "", false,
                    new LaunchedClient.Licence(LaunchedClient.LicenceState.OK, 0, Optional.empty()));
        }
    }
}
