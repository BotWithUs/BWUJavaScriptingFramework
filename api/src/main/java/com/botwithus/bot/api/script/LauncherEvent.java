package com.botwithus.bot.api.script;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Something the launcher service reported, or a change in the host's link to
 * it. Delivered through {@link ClientLauncher#onEvent(java.util.function.Consumer)}.
 *
 * <p>The first eight mirror the service's events. The last three are the
 * host's own: {@link ServiceLost} once per loss of the service,
 * {@link ServiceRestored} when it is back (never for the first connection), and
 * {@link EventsDropped} when events were missed. After either of the last two,
 * call {@link ClientLauncher#clients()} to see where things stand: missed events
 * are not replayed.</p>
 */
public sealed interface LauncherEvent {

    /**
     * A client started: a launch reached its first record, or auto-restart
     * replaced the process (then {@link LaunchedClient#restartOf()} is set).
     *
     * @param client the full record
     */
    record ClientStarted(LaunchedClient client) implements LauncherEvent {
        public ClientStarted {
            Objects.requireNonNull(client, "client");
        }
    }

    /**
     * A client's launch moved on.
     *
     * @param clientId the client
     * @param state    its new state
     * @param stage    the step it is at, when the service says
     * @param code     for {@link LaunchedClient.State#FAILED}, the failure code
     * @param message  English text about the step, for logs
     */
    record ClientStateChanged(String clientId, LaunchedClient.State state, Optional<String> stage,
                              Optional<String> code, Optional<String> message) implements LauncherEvent {
        public ClientStateChanged {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * A client's process exited.
     *
     * @param clientId the client
     * @param exitCode the process exit code
     * @param reason   why
     */
    record ClientExited(String clientId, long exitCode, ExitReason reason) implements LauncherEvent {
        public ClientExited {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The launcher has a new agent; clients injected before it are now stale.
     *
     * @param sha the new agent's SHA-256
     */
    record AgentUpdated(String sha) implements LauncherEvent {
        public AgentUpdated {
            Objects.requireNonNull(sha, "sha");
        }
    }

    /**
     * The state of a data update changed.
     *
     * @param phase               where the update is
     * @param stagedSha           the update waiting to be applied, if any
     * @param hostsBlocking       how many hosts must close before it applies
     * @param applyWhenHostsClose whether it applies by itself once they do
     */
    record DataUpdateAvailable(DataPhase phase, Optional<String> stagedSha, int hostsBlocking,
                               boolean applyWhenHostsClose) implements LauncherEvent {
        public DataUpdateAvailable {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(stagedSha, "stagedSha");
        }
    }

    /**
     * A data update was applied.
     *
     * @param sha the data now installed
     */
    record DataUpdateApplied(String sha) implements LauncherEvent {
        public DataUpdateApplied {
            Objects.requireNonNull(sha, "sha");
        }
    }

    /**
     * The licence link, or one client's licence, changed.
     *
     * @param link     the link: {@code "live"}, {@code "reconnecting"},
     *                 {@code "terminal"} or {@code "closed"}
     * @param clientId the client, when the change is one client's licence
     * @param state    that licence's state
     * @param failures its consecutive failures
     */
    record LicenceChanged(String link, Optional<String> clientId, Optional<LaunchedClient.LicenceState> state,
                          OptionalInt failures) implements LauncherEvent {
        public LicenceChanged {
            Objects.requireNonNull(link, "link");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(failures, "failures");
        }
    }

    /**
     * The service is shutting down.
     *
     * @param closeClients whether it is closing the clients too
     */
    record ServiceShuttingDown(boolean closeClients) implements LauncherEvent { }

    /**
     * The host lost the service. Calls fail until {@link ServiceRestored}.
     *
     * @param code {@link LauncherException#SERVICE_UNAVAILABLE} or
     *             {@link LauncherException#SERVICE_STOPPED}
     */
    record ServiceLost(String code) implements LauncherEvent {
        public ServiceLost {
            Objects.requireNonNull(code, "code");
        }
    }

    /** The host has the service again after a {@link ServiceLost}. */
    record ServiceRestored() implements LauncherEvent { }

    /**
     * Events were missed: the service's sequence skipped, or this listener fell
     * too far behind.
     *
     * @param count how many, as far as the host can tell
     */
    record EventsDropped(long count) implements LauncherEvent { }

    /** Why a client exited. */
    enum ExitReason {
        /** {@link ClientLauncher#stop(String, StopMode)} or the launcher stopped it. */
        STOPPED,
        /** Its licence lapsed. */
        LICENCE,
        /** The agent refused its launch details. */
        DESCRIPTOR,
        UNKNOWN
    }

    /** Where a data update is. */
    enum DataPhase { CURRENT, DOWNLOADING, STAGED, APPLYING, ERROR, UNKNOWN }
}
