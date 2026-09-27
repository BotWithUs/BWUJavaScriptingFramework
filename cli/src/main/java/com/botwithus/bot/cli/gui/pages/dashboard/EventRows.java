package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientRef;
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

import java.nio.file.Path;
import java.util.function.Function;

/** Describes host events as Events-tab lines. */
final class EventRows {

    /** The client column of a host-wide event. */
    static final String NO_CLIENT = "-";

    private EventRows() {}

    /**
     * @param labels the label of the client a client event is about
     */
    static EventRow of(HostEvent event, Function<ClientRef, String> labels) {
        return switch (event) {
            case HostEvent.ClientEvent e -> new EventRow(e.at(), type(e), labels.apply(e.client()), detail(e));
            case ScriptLoadFailed e -> new EventRow(e.at(), "ScriptLoadFailed", NO_CLIENT,
                    fileName(e.jar()) + " · " + oneLine(e.cause()));
            case ManagementAction e -> new EventRow(e.at(), "ManagementAction", NO_CLIENT,
                    e.script() + " · " + e.call() + " " + e.target() + " → " + e.result());
            case ManagementScriptCrashed e -> new EventRow(e.at(), "ManagementScriptCrashed", NO_CLIENT,
                    e.script() + " · " + crash(e.crash()));
        };
    }

    private static String type(HostEvent.ClientEvent event) {
        return switch (event) {
            case ClientOpened _ -> "ClientOpened";
            case ClientIdentified _ -> "ClientIdentified";
            case ClientResumed _ -> "ClientResumed";
            case ClientClosed _ -> "ClientClosed";
            case ClientForgotten _ -> "ClientForgotten";
            case ConnectionLost _ -> "ConnectionLost";
            case ReconnectStateChanged e -> "Reconnect." + reconnectName(e.state());
            case ScriptStarted _ -> "ScriptStarted";
            case ScriptStopped _ -> "ScriptStopped";
            case ScriptStalled _ -> "ScriptStalled";
            case ScriptCrashed _ -> "ScriptCrashed";
        };
    }

    private static String detail(HostEvent.ClientEvent event) {
        return switch (event) {
            case ClientOpened e -> "Pipe " + e.client().pipe() + " connected";
            case ClientIdentified e -> e.name().map(n -> "Identified as " + n).orElse("Identified");
            case ClientResumed e -> e.previousPipe().map(p -> "Back from " + p).orElse("Back from an earlier session");
            case ClientClosed e -> switch (e.cause()) {
                case DISCONNECTED -> "Disconnected";
                case CONNECTION_LOST -> "Removed after the connection was lost";
            };
            case ClientForgotten _ -> "Forgotten";
            case ConnectionLost e -> e.cause() != null ? oneLine(e.cause()) : "Pipe closed";
            case ReconnectStateChanged e -> reconnectDetail(e.state());
            case ScriptStarted e -> e.script();
            case ScriptStopped e -> e.script();
            case ScriptStalled e -> e.script() + " · inside onLoop() past the stall limit";
            case ScriptCrashed e -> e.script() + " · " + crash(e.crash());
        };
    }

    private static String reconnectName(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Connected _ -> "Connected";
            case ReconnectState.Disconnected _ -> "Disconnected";
            case ReconnectState.Reconnecting _ -> "Reconnecting";
            case ReconnectState.GivingUp _ -> "GivingUp";
        };
    }

    private static String reconnectDetail(ReconnectState state) {
        return switch (state) {
            case ReconnectState.Connected _ -> "Back";
            case ReconnectState.Disconnected d -> d.cause() != null ? oneLine(d.cause()) : "Pipe closed";
            case ReconnectState.Reconnecting r -> "attempt " + r.attempt() + " · next " + r.nextDelayMs() + " ms";
            case ReconnectState.GivingUp g -> "gave up after " + g.attempts() + " attempts";
        };
    }

    /** "onLoop · NullPointerException". */
    static String crash(LastCrash crash) {
        String phase = switch (crash.phase()) {
            case ON_START -> "onStart";
            case ON_LOOP -> "onLoop";
            case ON_STOP -> "onStop";
            case ON_CONFIG_UPDATE -> "onConfigUpdate";
        };
        return phase + " · " + typeOf(crash.cause());
    }

    /** "IllegalStateException: pipe gone", or just the type when there is no message. */
    static String oneLine(Throwable cause) {
        String message = cause.getMessage();
        String type = typeOf(cause);
        return message == null || message.isBlank() ? type : type + ": " + message.lines().findFirst().orElse("");
    }

    private static String typeOf(Throwable cause) {
        return cause != null ? cause.getClass().getSimpleName() : "Error";
    }

    private static String fileName(Path jar) {
        Path name = jar.getFileName();
        return name != null ? name.toString() : jar.toString();
    }
}
