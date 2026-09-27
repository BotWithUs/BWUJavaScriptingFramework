package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import com.botwithus.bot.core.rpc.ReconnectController;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a {@link HostEvent} into the {@link Alert} it is worth, if any.
 *
 * <ul>
 *   <li>Client stops responding: its pipe dropped ({@code ConnectionLost}).</li>
 *   <li>Client comes back: its reconnect succeeded, or the same account came back
 *       on a new pipe ({@code ClientResumed}).</li>
 *   <li>Client closed: its reconnect gave up because the game exited, or the host
 *       removed a dead connection — reported once per connection. A reconnect the
 *       user stopped is their own doing and is not reported, even if the game has
 *       exited by then.</li>
 *   <li>Script crashes (management scripts too), script stalls, a JAR fails to
 *       load, a management script acts: one alert each.</li>
 * </ul>
 *
 * <p>What leaves the machine is kept to display names: a crash names the exception
 * class and the hook, never the exception message; a load failure names the file,
 * never its path; a client is never named by its account id. The name for a
 * client is remembered per {@link ClientKey} from the events before, so an alert
 * about a client whose connection is already gone still names it. Thread-safe.</p>
 */
public final class AlertClassifier {

    private final ClientDirectory directory;
    private final Map<ClientKey, String> names = new ConcurrentHashMap<>();
    /** Clients whose close was already reported, so the removal that follows is not reported again. */
    private final Set<ClientKey> closeReported = ConcurrentHashMap.newKeySet();

    public AlertClassifier(ClientDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** The alert {@code event} is worth, if any. */
    public Optional<Alert> classify(HostEvent event) {
        return switch (event) {
            case HostEvent.ClientEvent clientEvent -> clientAlert(clientEvent);
            case HostEvent.ScriptLoadFailed failed -> Optional.of(new Alert(AlertKind.JAR_LOAD_FAILED,
                    fileName(failed.jar()) + " failed to load", failed.cause().getClass().getSimpleName(),
                    failed.at()));
            case HostEvent.ManagementAction action -> Optional.of(new Alert(AlertKind.MANAGEMENT_ACTION,
                    action.script() + " · " + action.call() + " · " + action.target(), action.result(), action.at()));
            case HostEvent.ManagementScriptCrashed crashed -> Optional.of(new Alert(AlertKind.SCRIPT_CRASH,
                    "Management script " + crashed.script() + " crashed", describe(crashed.crash()), crashed.at()));
        };
    }

    /**
     * The name to show for {@code client}: the last name it was known by, else its
     * pipe, else {@link LiveClientDirectory#UNNAMED}. Never its account id.
     */
    public String nameOf(ClientRef client) {
        String known = names.get(client.key());
        if (known != null) {
            return known;
        }
        return client.hasPipe() ? client.pipe() : LiveClientDirectory.UNNAMED;
    }

    private Optional<Alert> clientAlert(HostEvent.ClientEvent event) {
        ClientRef client = event.client();
        learnName(event);
        String name = nameOf(client);
        return switch (event) {
            case HostEvent.ConnectionLost lost ->
                    alert(AlertKind.CLIENT_LOST, name + " stopped responding", lost.at());
            case HostEvent.ReconnectStateChanged changed -> reconnectAlert(changed, name);
            case HostEvent.ClientResumed resumed -> {
                closeReported.remove(client.key());
                yield alert(AlertKind.CLIENT_BACK, name + " is back", resumed.at());
            }
            case HostEvent.ClientClosed closed -> closed.cause() == HostEvent.CloseCause.CONNECTION_LOST
                    ? closedOnce(client.key(), name, closed.at()) : Optional.empty();
            case HostEvent.ScriptCrashed crashed -> Optional.of(new Alert(AlertKind.SCRIPT_CRASH,
                    crashed.script() + " crashed on " + name, describe(crashed.crash()), crashed.at()));
            case HostEvent.ScriptStalled stalled ->
                    alert(AlertKind.SCRIPT_STALL, stalled.script() + " stalled on " + name, stalled.at());
            case HostEvent.ClientOpened _, HostEvent.ClientIdentified _ -> {
                closeReported.remove(client.key());
                yield Optional.empty();
            }
            case HostEvent.ClientForgotten _ -> {
                names.remove(client.key());
                closeReported.remove(client.key());
                yield Optional.empty();
            }
            case HostEvent.ScriptStarted _, HostEvent.ScriptStopped _ -> Optional.empty();
        };
    }

    /** Remembers the best name for the event's client: the one the event carries, else the directory's. */
    private void learnName(HostEvent.ClientEvent event) {
        Optional<String> carried = switch (event) {
            case HostEvent.ClientIdentified identified -> identified.name();
            default -> Optional.empty();
        };
        carried.or(() -> directory.displayName(event.client()))
                .filter(name -> !name.isBlank())
                .ifPresent(name -> names.put(event.client().key(), name));
    }

    private Optional<Alert> reconnectAlert(HostEvent.ReconnectStateChanged changed, String name) {
        ClientKey key = changed.client().key();
        return switch (changed.state()) {
            case ReconnectState.Connected _ -> {
                closeReported.remove(key);
                yield alert(AlertKind.CLIENT_BACK, name + " is back", changed.at());
            }
            case ReconnectState.GivingUp gaveUp ->
                    !ReconnectController.wasStoppedOnRequest(gaveUp) && directory.hasExited(changed.client())
                            ? closedOnce(key, name, changed.at()) : Optional.empty();
            case ReconnectState.Disconnected _, ReconnectState.Reconnecting _ -> Optional.empty();
        };
    }

    private Optional<Alert> closedOnce(ClientKey key, String name, Instant at) {
        if (!closeReported.add(key)) {
            return Optional.empty();
        }
        return alert(AlertKind.CLIENT_CLOSED, name + " closed", at);
    }

    private static Optional<Alert> alert(AlertKind kind, String headline, Instant at) {
        return Optional.of(new Alert(kind, headline, at));
    }

    /** {@code NullPointerException in onLoop()}: the exception's class and the hook, never its message. */
    private static String describe(LastCrash crash) {
        String type = crash.cause() == null ? "Error" : crash.cause().getClass().getSimpleName();
        return type + " in " + hook(crash.phase()) + "()";
    }

    private static String hook(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart";
            case ON_LOOP -> "onLoop";
            case ON_STOP -> "onStop";
            case ON_CONFIG_UPDATE -> "onConfigUpdate";
        };
    }

    private static String fileName(Path jar) {
        Path name = jar.getFileName();
        return name == null ? "A JAR" : name.toString();
    }
}
