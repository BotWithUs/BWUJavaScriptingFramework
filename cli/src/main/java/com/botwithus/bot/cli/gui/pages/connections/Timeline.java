package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ManagementAction;
import com.botwithus.bot.cli.events.HostEvent.ManagementScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.events.HostEvent.ScriptStopped;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A client's history as the detail pane tells it: newest first, one line per
 * event, a run of retries shown as its latest attempt only.
 */
public final class Timeline {

    /** The most lines the pane shows. */
    public static final int MAX_ENTRIES = 12;

    private Timeline() {
    }

    /** The lines for {@code events}, which are oldest first as the history keeps them. */
    public static List<TimelineEntry> of(List<HostEvent> events) {
        List<TimelineEntry> lines = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && lines.size() < MAX_ENTRIES; i--) {
            HostEvent event = events.get(i);
            boolean isOlderRetry = isRetry(event) && i + 1 < events.size() && isRetry(events.get(i + 1));
            if (!isOlderRetry) {
                entryFor(event).ifPresent(lines::add);
            }
        }
        return List.copyOf(lines);
    }

    /**
     * When the client's current outage began: the oldest drop since it last came
     * back. Empty when it is not down, or no drop was recorded.
     */
    public static Optional<Instant> droppedAt(List<HostEvent> events) {
        Optional<Instant> start = Optional.empty();
        for (int i = events.size() - 1; i >= 0; i--) {
            HostEvent event = events.get(i);
            if (isRecovery(event)) {
                return start;
            }
            if (isDrop(event)) {
                start = Optional.of(event.at());
            }
        }
        return start;
    }

    private static boolean isRetry(HostEvent event) {
        return switch (event) {
            case ReconnectStateChanged changed -> switch (changed.state()) {
                case ReconnectState.Reconnecting _ -> true;
                case ReconnectState.Connected _, ReconnectState.Disconnected _, ReconnectState.GivingUp _ -> false;
            };
            default -> false;
        };
    }

    private static boolean isDrop(HostEvent event) {
        return switch (event) {
            case ConnectionLost _ -> true;
            case ReconnectStateChanged changed -> switch (changed.state()) {
                case ReconnectState.Disconnected _ -> true;
                case ReconnectState.Connected _, ReconnectState.Reconnecting _, ReconnectState.GivingUp _ -> false;
            };
            default -> false;
        };
    }

    private static boolean isRecovery(HostEvent event) {
        return switch (event) {
            case ClientOpened _, ClientIdentified _, ClientResumed _ -> true;
            case ReconnectStateChanged changed -> switch (changed.state()) {
                case ReconnectState.Connected _ -> true;
                case ReconnectState.Disconnected _, ReconnectState.Reconnecting _, ReconnectState.GivingUp _ -> false;
            };
            default -> false;
        };
    }

    private static Optional<TimelineEntry> entryFor(HostEvent event) {
        Instant at = event.at();
        return switch (event) {
            case ClientOpened opened -> line(at, Tone.NEUTRAL, "Pipe opened", opened.client().pipe());
            case ClientIdentified identified -> line(at, Tone.OK, "Connected",
                    identified.name().map(name -> "account read · " + name).orElse("account read"));
            case ClientResumed resumed -> line(at, Tone.OK, "Resumed",
                    resumed.previousPipe().map(pipe -> "back from " + pipe).orElse("back after a host restart"));
            case ClientClosed closed -> switch (closed.cause()) {
                case DISCONNECTED -> line(at, Tone.NEUTRAL, "Disconnected", "");
                case CONNECTION_LOST -> line(at, Tone.ERROR, "Client closed", "");
            };
            case ClientForgotten _ -> line(at, Tone.NEUTRAL, "Forgotten", "");
            case ConnectionLost lost -> line(at, Tone.ERROR, "Connection lost", reasonOf(lost.cause()));
            case ReconnectStateChanged changed -> reconnectLine(at, changed.state());
            case ScriptStarted started -> line(at, Tone.OK, "Started", started.script());
            case ScriptStopped stopped -> line(at, Tone.NEUTRAL, "Stopped", stopped.script());
            case ScriptStalled stalled -> line(at, Tone.WARN, "Stalled", stalled.script());
            case ScriptCrashed crashed -> line(at, Tone.ERROR, "Crashed", crashed.script());
            case ScriptLoadFailed _, ManagementAction _, ManagementScriptCrashed _ -> Optional.empty();
        };
    }

    private static Optional<TimelineEntry> reconnectLine(Instant at, ReconnectState state) {
        return switch (state) {
            case ReconnectState.Connected _ -> line(at, Tone.OK, "Reconnected", "");
            case ReconnectState.Disconnected d -> line(at, Tone.ERROR, "Connection lost", reasonOf(d.cause()));
            case ReconnectState.Reconnecting r -> line(at, Tone.WARN, "Reconnecting", "attempt " + r.attempt()
                    + ", next in " + ConnectionText.seconds(Duration.ofMillis(r.nextDelayMs())));
            case ReconnectState.GivingUp g -> line(at, Tone.WARN, "Stopped retrying",
                    "after " + g.attempts() + (g.attempts() == 1 ? " attempt" : " attempts"));
        };
    }

    private static String reasonOf(Throwable cause) {
        return cause == null || cause.getMessage() == null ? "" : cause.getMessage();
    }

    private static Optional<TimelineEntry> line(Instant at, Tone tone, String lead, String detail) {
        return Optional.of(new TimelineEntry(at, tone, lead, detail));
    }
}
