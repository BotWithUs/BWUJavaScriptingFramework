package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.script.ClientLauncher;
import com.botwithus.bot.api.script.LaunchHandle;
import com.botwithus.bot.api.script.LaunchOptions;
import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.api.script.StopMode;
import com.botwithus.bot.core.launcher.HostClientLauncher;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One management script's view of the host's {@link ClientLauncher}, limited
 * to its targets, with every call recorded in the orchestrator audit log
 * (launcher ADR 0007, section 10.1).
 *
 * <p>The scope is matched on the service's {@code accountId}, the account UUID
 * a script's targets name. A script that does not manage the whole host:</p>
 * <ul>
 *   <li>sees only the accounts and clients its targets cover, and only their
 *       events;</li>
 *   <li>cannot launch on, or stop a client of, an account outside them;</li>
 *   <li>cannot launch on an account whose group it is paused on, though it may
 *       still see and stop that group's clients ({@link Scope#isPausedOn}).</li>
 * </ul>
 * <p>A refused call throws {@link LauncherException#NOT_PERMITTED}. That differs
 * from {@link ScopedClientOrchestrator}, which returns a failed result: every
 * other {@code ClientLauncher} failure is an exception, so its refusals are too.
 * Like the service's own limits, none of this is a security boundary.</p>
 */
public final class ScopedClientLauncher implements ClientLauncher {

    private static final String REFUSED = "refused: ";
    private static final String FAILED = "failed: ";
    private static final String DONE = "done";
    private static final String CALL_ACCOUNTS = "launcher.accounts";
    private static final String CALL_CLIENTS = "launcher.clients";
    private static final String CALL_LAUNCH = "launcher.launch";
    private static final String CALL_STOP = "launcher.stop";
    private static final String CALL_ON_EVENT = "launcher.onEvent";
    private static final String EVERY_ACCOUNT = "every account";
    private static final String EVERY_CLIENT = "every client";

    private final String script;
    private final Supplier<HostClientLauncher> delegate;
    private final Supplier<Scope> scope;
    private final OrchestratorAuditLog audit;

    /**
     * @param script   the management script this launcher is for
     * @param delegate the host's launcher; asked on every call, so a launcher the
     *                 composition root sets later is picked up
     * @param scope    the script's targets as they are now; asked on every call
     * @param audit    where every call is recorded
     */
    public ScopedClientLauncher(String script, Supplier<HostClientLauncher> delegate, Supplier<Scope> scope,
                                OrchestratorAuditLog audit) {
        this.script = Objects.requireNonNull(script, "script");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public List<LauncherAccount> accounts() {
        List<LauncherAccount> all = recordingFailure(CALL_ACCOUNTS, EVERY_ACCOUNT, () -> delegate.get().accounts());
        Scope now = scope.get();
        List<LauncherAccount> visible = all.stream().filter(a -> now.coversClient(Optional.of(a.id()))).toList();
        audit.record(script, CALL_ACCOUNTS, EVERY_ACCOUNT, visible.size() + " visible");
        return visible;
    }

    @Override
    public List<LaunchedClient> clients() {
        List<LaunchedClient> all = recordingFailure(CALL_CLIENTS, EVERY_CLIENT, () -> delegate.get().clients());
        Scope now = scope.get();
        List<LaunchedClient> visible = all.stream()
                .filter(c -> now.coversClient(Optional.of(c.accountId()))).toList();
        audit.record(script, CALL_CLIENTS, EVERY_CLIENT, visible.size() + " visible");
        return visible;
    }

    @Override
    public LaunchHandle launch(String accountId, LaunchOptions options) {
        Objects.requireNonNull(accountId, "accountId");
        String target = "account " + accountId;
        Scope now = scope.get();
        Optional<String> account = Optional.of(accountId);
        if (!now.coversClient(account)) {
            throw refused(CALL_LAUNCH, target, ScopedClientOrchestrator.NOT_IN_TARGETS);
        }
        if (now.isPausedOn(account)) {
            throw refused(CALL_LAUNCH, target, ScopedClientOrchestrator.PAUSED);
        }
        LaunchHandle handle = recordingFailure(CALL_LAUNCH, target, () -> delegate.get().launch(accountId, options));
        audit.record(script, CALL_LAUNCH, target, "queued as " + handle.clientId());
        return handle;
    }

    @Override
    public void stop(String clientId, StopMode mode) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(mode, "mode");
        Scope now = scope.get();
        Optional<String> account = accountOfClient(clientId);
        String target = "client " + clientId + account.map(a -> " of account " + a).orElse("") + " (" + mode + ")";
        if (!now.isWholeHost() && !now.coversClient(account)) {
            throw refused(CALL_STOP, target, ScopedClientOrchestrator.NOT_IN_TARGETS);
        }
        recordingFailure(CALL_STOP, target, () -> {
            delegate.get().stop(clientId, mode);
            return clientId;
        });
        audit.record(script, CALL_STOP, target, DONE);
    }

    @Override
    public AutoCloseable onEvent(Consumer<LauncherEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        AutoCloseable subscription = recordingFailure(CALL_ON_EVENT, EVERY_CLIENT,
                () -> delegate.get().onEvent(event -> {
                    if (isVisible(event)) {
                        listener.accept(event);
                    }
                }));
        audit.record(script, CALL_ON_EVENT, EVERY_CLIENT, DONE);
        return subscription;
    }

    /** Whether the script may see an event: one about a client only if it may see the client. */
    private boolean isVisible(LauncherEvent event) {
        return switch (event) {
            case LauncherEvent.ClientStarted started -> scope.get()
                    .coversClient(Optional.of(started.client().accountId()));
            case LauncherEvent.ClientStateChanged changed -> coversClientId(changed.clientId());
            case LauncherEvent.ClientExited exited -> coversClientId(exited.clientId());
            case LauncherEvent.LicenceChanged licence -> licence.clientId().map(this::coversClientId).orElse(true);
            default -> true;
        };
    }

    private boolean coversClientId(String clientId) {
        Scope now = scope.get();
        return now.isWholeHost() || now.coversClient(accountOfClient(clientId));
    }

    /** The account a client is on: what the host has seen, else the service's current list. */
    private Optional<String> accountOfClient(String clientId) {
        HostClientLauncher host = delegate.get();
        Optional<String> known = host.accountOf(clientId);
        if (known.isPresent()) {
            return known;
        }
        try {
            return host.clients().stream().filter(c -> c.clientId().equals(clientId))
                    .map(LaunchedClient::accountId).findFirst();
        } catch (LauncherException e) {
            return Optional.empty();
        }
    }

    private LauncherException refused(String call, String target, String reason) {
        audit.record(script, call, target, REFUSED + reason);
        return new LauncherException(LauncherException.NOT_PERMITTED, call + " on " + target + ": " + reason);
    }

    /** Runs {@code action}; a failure is recorded with its code before it is rethrown. */
    private <T> T recordingFailure(String call, String target, Supplier<T> action) {
        try {
            return action.get();
        } catch (LauncherException e) {
            audit.record(script, call, target, FAILED + e.code());
            throw e;
        }
    }
}
