package com.botwithus.bot.cli.events;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.ReconnectState;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Something that happened in the host, as opposed to in the game.
 *
 * <p>These events live here, not in the scripting API: the API's
 * {@code GameEvent} is public and sealed, and none of these — a client pipe
 * closing, a script stalling, a JAR failing to load — is something a script
 * subscribes to. They travel on the {@link HostEventBus}, which, unlike each
 * connection's event bus, exists whether or not any client is connected.</p>
 *
 * <p>The hierarchy is closed, so a consumer switches over it exhaustively and
 * the compiler flags every switch when a variant is added. Events about one
 * client implement {@link ClientEvent}; the rest concern the host as a whole.</p>
 */
public sealed interface HostEvent {

    /** When the event happened. */
    Instant at();

    /** An event about one client. */
    sealed interface ClientEvent extends HostEvent {

        /** The client the event is about. */
        ClientRef client();
    }

    /** Why a client left the host's connection table. */
    enum CloseCause {
        /** The user, or a command, disconnected or forgot it. */
        DISCONNECTED,
        /** The host found the connection dead and removed it. */
        CONNECTION_LOST
    }

    /** A client's connection was added to the host's connection table. */
    record ClientOpened(ClientRef client, Instant at) implements ClientEvent {
        public ClientOpened {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * The host read which account a client is on, and settled the key it is known
     * by. {@link ClientEvent#client()} carries that key: the account's when the
     * client reported a real account UUID, else still its pipe's. Every later
     * event about the client carries the same key, so a subscriber that keeps
     * state per client moves the pipe's state to this key here.
     *
     * @param name the name the client showed when it was identified, if any
     */
    record ClientIdentified(ClientRef client, Optional<String> name, Instant at)
            implements ClientEvent {
        public ClientIdentified {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A client the host already knew came back: the same account identified on
     * a new pipe, usually because its game was restarted. Follows the
     * {@link ClientIdentified} for the new pipe.
     *
     * @param client       the client, on its new pipe
     * @param previousPipe the pipe it was last seen on; empty when it was
     *                     remembered from an earlier run of the host
     */
    record ClientResumed(ClientRef client, Optional<String> previousPipe, Instant at)
            implements ClientEvent {
        public ClientResumed {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(previousPipe, "previousPipe");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A client's connection was removed from the host's connection table. */
    record ClientClosed(ClientRef client, CloseCause cause, Instant at) implements ClientEvent {
        public ClientClosed {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * The user forgot a client that had gone: the host dropped it and everything
     * it remembered about it, including its history. Recorded host-wide, since
     * the client's own history is what was dropped.
     */
    record ClientForgotten(ClientRef client, Instant at) implements ClientEvent {
        public ClientForgotten {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A client's pipe dropped mid-session; reconnect attempts follow.
     *
     * @param cause what surfaced the drop, or {@code null} if it was inferred
     */
    record ConnectionLost(ClientRef client, Throwable cause, Instant at) implements ClientEvent {
        public ConnectionLost {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A client's reconnect state machine moved to {@code state}. */
    record ReconnectStateChanged(ClientRef client, ReconnectState state, Instant at)
            implements ClientEvent {
        public ReconnectStateChanged {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A script finished {@code onStart} on a client and entered its loop. */
    record ScriptStarted(ClientRef client, String script, Instant at) implements ClientEvent {
        public ScriptStarted {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A script's run on a client ended. */
    record ScriptStopped(ClientRef client, String script, Instant at) implements ClientEvent {
        public ScriptStopped {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(at, "at");
        }
    }

    /** The liveness watchdog judged a script on a client unresponsive. */
    record ScriptStalled(ClientRef client, String script, Instant at) implements ClientEvent {
        public ScriptStalled {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A script's lifecycle hook threw on a client. */
    record ScriptCrashed(ClientRef client, String script, LastCrash crash, Instant at)
            implements ClientEvent {
        public ScriptCrashed {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(crash, "crash");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A script JAR could not be loaded. Recorded whether or not any client is connected. */
    record ScriptLoadFailed(Path jar, Throwable cause, Instant at) implements HostEvent {
        public ScriptLoadFailed {
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A management script acted on the host through its orchestrator.
     *
     * @param script the management script that made the call
     * @param call   the orchestrator operation, e.g. starting a script
     * @param target what the call acted on, as shown to the user
     * @param result the outcome, as shown to the user
     */
    record ManagementAction(String script, String call, String target, String result, Instant at)
            implements HostEvent {
        public ManagementAction {
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(at, "at");
        }
    }

    /** A management script's lifecycle hook threw. */
    record ManagementScriptCrashed(String script, LastCrash crash, Instant at)
            implements HostEvent {
        public ManagementScriptCrashed {
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(crash, "crash");
            Objects.requireNonNull(at, "at");
        }
    }
}
