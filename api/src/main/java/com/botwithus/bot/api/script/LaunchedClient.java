package com.botwithus.bot.api.script;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A client the launcher service manages, as it reported it.
 *
 * @param clientId       the service's id; stable across auto-restarts
 * @param pid            the game process id; changes on an auto-restart
 * @param accountId      the account's UUID (see {@link LauncherAccount#id()})
 * @param accountName    the account's display name
 * @param characterIndex the Steam character, or {@link LaunchOptions#NO_CHARACTER}
 * @param kind           what kind of client it is
 * @param origin         who launched it
 * @param launchedBy     the host that launched it, when {@code origin} is
 *                       {@link Origin#AUTOMATION}
 * @param restartOf      the previous pid, when auto-restart replaced the process
 * @param state          where the launch is
 * @param startedAtMs    when the launch started, in unix milliseconds
 * @param agentSha       the SHA-256 of the agent injected into it; empty before injection
 * @param isAgentStale   whether a newer agent has been published since
 * @param licence        the state of its licence
 */
public record LaunchedClient(String clientId, long pid, String accountId, String accountName,
                             int characterIndex, Kind kind, Origin origin, Optional<LaunchedBy> launchedBy,
                             OptionalLong restartOf, State state, long startedAtMs, String agentSha,
                             boolean isAgentStale, Licence licence) {

    public LaunchedClient {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(launchedBy, "launchedBy");
        Objects.requireNonNull(restartOf, "restartOf");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(agentSha, "agentSha");
        Objects.requireNonNull(licence, "licence");
    }

    /** Where a launch is. {@link #UNKNOWN} is a state this host does not know yet. */
    public enum State {
        QUEUED, SPAWNING, INJECTING,
        /** The agent is injected and its pipe is up; the last state the service can know. */
        INJECTED,
        FAILED, EXITED, UNKNOWN
    }

    /** What kind of client it is. {@link #ATTACHED} occurs only in development builds. */
    public enum Kind { JAGEX, STEAM, ATTACHED, UNKNOWN }

    /** Who launched it: the launcher's own UI, or a host over automation. */
    public enum Origin { UI, AUTOMATION, UNKNOWN }

    /** A licence's state; see {@link Licence}. */
    public enum LicenceState {
        /** The last refresh succeeded. */
        OK,
        /** Refreshes are failing; the client keeps running for now. */
        RETRYING,
        /** The licence lapsed or will; the client exits. */
        DROPPED,
        /** The service could not take the licence over after a restart. */
        UNTRACKED,
        UNKNOWN
    }

    /**
     * The host connection that launched a client.
     *
     * @param pid      the host's process id
     * @param hostKind {@code "java"} or {@code "native"}
     * @param label    the host's display label, if it gave one
     */
    public record LaunchedBy(long pid, String hostKind, Optional<String> label) {
        public LaunchedBy {
            Objects.requireNonNull(hostKind, "hostKind");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * A client's licence.
     *
     * @param state     its state
     * @param failures  consecutive failed refreshes
     * @param lastError the last refresh error, if one was recorded
     */
    public record Licence(LicenceState state, int failures, Optional<String> lastError) {
        public Licence {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(lastError, "lastError");
        }
    }
}
