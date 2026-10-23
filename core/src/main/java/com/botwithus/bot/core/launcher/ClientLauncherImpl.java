package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LaunchHandle;
import com.botwithus.bot.api.script.LaunchOptions;
import com.botwithus.bot.api.script.LaunchOutcome;
import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.api.script.StopMode;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * The host's {@link HostClientLauncher}: every call goes to the service over
 * the process's one {@link LauncherService} connection.
 *
 * <p><b>Attach.</b> When the service reports a launched client
 * {@linkplain LaunchedClient.State#INJECTED injected} (normative: the agent's
 * pipe is up, launcher ADR 0007, section 4.2), this calls the attach callback
 * with the client's pid and completes the launch's outcome with what it
 * returned. The callback belongs to the composition root, which owns the
 * connection table. Attach runs on its own thread, never on the reader.</p>
 *
 * <p><b>After the service comes back</b>, each launch still waiting is looked up
 * again with {@code client.status}; no event is replayed or synthesised.</p>
 */
public final class ClientLauncherImpl implements HostClientLauncher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClientLauncherImpl.class);

    private final LauncherService service;
    private final IntFunction<AttachResult> attacher;
    private final Map<String, PendingLaunch> pending = new ConcurrentHashMap<>();
    private final Map<String, String> accountByClient = new ConcurrentHashMap<>();
    private final Runnable unsubscribe;

    /**
     * @param service  the process's connection to the service
     * @param attacher attaches the host to a pid; blocks until it has a result
     */
    public ClientLauncherImpl(LauncherService service, IntFunction<AttachResult> attacher) {
        this.service = Objects.requireNonNull(service, "service");
        this.attacher = Objects.requireNonNull(attacher, "attacher");
        this.unsubscribe = service.addListener(this::onServiceEvent);
    }

    @Override
    public List<LauncherAccount> accounts() {
        return LauncherDecoder.accounts(service.call(LauncherProtocol.METHOD_ACCOUNTS_LIST, Envelope.emptyBody()));
    }

    @Override
    public LaunchHandle launch(String accountId, LaunchOptions options) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(options, "options");
        Value body = service.call(LauncherProtocol.METHOD_CLIENT_LAUNCH,
                LauncherRequests.clientLaunch(accountId, options.characterIndex()));
        String clientId = WireValues.string(body, "clientId").orElseThrow(() -> new LauncherException(
                WireError.UNKNOWN_CODE, "client.launch answered without a clientId"));
        accountByClient.put(clientId, accountId);
        PendingLaunch launch = new PendingLaunch(clientId);
        pending.put(clientId, launch);
        launch.outcome.whenComplete((outcome, failure) -> pending.remove(clientId, launch));
        Thread.ofVirtual().name("launcher-launch-sync").start(() -> reconcile(launch));
        return launch;
    }

    @Override
    public void stop(String clientId, StopMode mode) {
        Objects.requireNonNull(mode, "mode");
        service.call(LauncherProtocol.METHOD_CLIENT_STOP, LauncherRequests.clientStop(clientId, mode));
    }

    @Override
    public List<LaunchedClient> clients() {
        List<LaunchedClient> clients = LauncherDecoder.clients(
                service.call(LauncherProtocol.METHOD_CLIENT_LIST, Envelope.emptyBody()));
        clients.forEach(c -> accountByClient.put(c.clientId(), c.accountId()));
        return clients;
    }

    @Override
    public AutoCloseable onEvent(Consumer<LauncherEvent> listener) {
        ListenerDispatch dispatch = new ListenerDispatch(listener);
        Runnable remove = service.addListener(dispatch::offer);
        return () -> {
            remove.run();
            dispatch.close();
        };
    }

    @Override
    public Optional<String> accountOf(String clientId) {
        return Optional.ofNullable(accountByClient.get(clientId));
    }

    /** Stops tracking launches; outcomes still pending complete as failed. */
    @Override
    public void close() {
        unsubscribe.run();
        pending.values().forEach(p -> p.complete(new LaunchOutcome.Failed(LauncherException.SERVICE_UNAVAILABLE,
                "the host is shutting down")));
    }

    /** Runs on the reader thread: hand work off, never block. */
    private void onServiceEvent(LauncherEvent event) {
        switch (event) {
            case LauncherEvent.ClientStarted started -> onStarted(started.client());
            case LauncherEvent.ClientStateChanged changed -> onStateChanged(changed);
            case LauncherEvent.ClientExited exited -> onExited(exited);
            case LauncherEvent.ServiceRestored _ -> pending.values().forEach(p ->
                    Thread.ofVirtual().name("launcher-launch-sync").start(() -> reconcile(p)));
            default -> {
                // Not about a launch this host is waiting on.
            }
        }
    }

    private void onStarted(LaunchedClient client) {
        accountByClient.put(client.clientId(), client.accountId());
        PendingLaunch launch = pending.get(client.clientId());
        if (launch != null) {
            apply(launch, client);
        }
    }

    private void onStateChanged(LauncherEvent.ClientStateChanged changed) {
        PendingLaunch launch = pending.get(changed.clientId());
        if (launch == null) {
            return;
        }
        switch (changed.state()) {
            // The event carries no pid; client.status does.
            case INJECTED -> Thread.ofVirtual().name("launcher-launch-sync").start(() -> reconcile(launch));
            case FAILED -> launch.complete(new LaunchOutcome.Failed(changed.code().orElse("launch_failed"),
                    changed.message().orElse("the launch failed")));
            default -> {
                // Still on its way.
            }
        }
    }

    private void onExited(LauncherEvent.ClientExited exited) {
        PendingLaunch launch = pending.get(exited.clientId());
        if (launch != null && !launch.isAttaching()) {
            launch.complete(exitedBeforeAttach(exited.reason(), exited.exitCode()));
        }
    }

    /** Looks the launch up and acts on where it is. */
    private void reconcile(PendingLaunch launch) {
        try {
            Value body = service.call(LauncherProtocol.METHOD_CLIENT_STATUS,
                    LauncherRequests.clientStatus(launch.clientId));
            apply(launch, LauncherDecoder.client(body));
        } catch (LauncherException e) {
            if (e.code().equals(LauncherException.CLIENT_NOT_FOUND)) {
                launch.complete(new LaunchOutcome.Failed(e.code(), e.getMessage()));
            } else {
                log.debug("Could not look up launch {} yet: {}", launch.clientId, e.getMessage());
            }
        }
    }

    private void apply(PendingLaunch launch, LaunchedClient client) {
        switch (client.state()) {
            case INJECTED -> startAttach(launch, client.pid());
            case FAILED -> launch.complete(new LaunchOutcome.Failed("launch_failed", "the launch failed"));
            case EXITED -> launch.complete(exitedBeforeAttach(LauncherEvent.ExitReason.UNKNOWN, -1L));
            default -> {
                // Still on its way; an event will move it on.
            }
        }
    }

    private void startAttach(PendingLaunch launch, long pid) {
        if (!launch.beginAttach()) {
            return;
        }
        Thread.ofVirtual().name("launcher-attach-" + pid).start(() -> launch.complete(attach(pid)));
    }

    private LaunchOutcome attach(long pid) {
        try {
            AttachResult result = attacher.apply(Math.toIntExact(pid));
            return switch (result) {
                case AttachResult.Attached attached -> new LaunchOutcome.Attached(attached.connectionName());
                case AttachResult.Failed failed -> new LaunchOutcome.AttachFailed(failed.message());
            };
        } catch (RuntimeException e) {
            log.warn("Attaching to pid {} failed", pid, e);
            return new LaunchOutcome.AttachFailed("attach to pid " + pid + " failed: " + e.getMessage());
        }
    }

    private static LaunchOutcome exitedBeforeAttach(LauncherEvent.ExitReason reason, long exitCode) {
        return new LaunchOutcome.Failed("exited",
                "the client exited before the host attached (" + reason + ", exit code " + exitCode + ")");
    }

    /** A launch whose outcome is not known yet. */
    private static final class PendingLaunch implements LaunchHandle {

        private final String clientId;
        private final CompletableFuture<LaunchOutcome> outcome = new CompletableFuture<>();
        private final AtomicBoolean isAttaching = new AtomicBoolean();

        PendingLaunch(String clientId) {
            this.clientId = clientId;
        }

        @Override
        public String clientId() {
            return clientId;
        }

        @Override
        public CompletionStage<LaunchOutcome> outcome() {
            return outcome.minimalCompletionStage();
        }

        boolean beginAttach() {
            return isAttaching.compareAndSet(false, true);
        }

        boolean isAttaching() {
            return isAttaching.get();
        }

        void complete(LaunchOutcome result) {
            if (outcome.complete(result)) {
                log.info("Launch {} finished: {}", clientId, result);
            }
        }
    }
}
