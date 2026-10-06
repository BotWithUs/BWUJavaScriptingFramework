package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ManagementAction;
import com.botwithus.bot.cli.events.HostEvent.ManagementScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.events.HostEvent.ScriptStopped;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;
import com.botwithus.bot.cli.gui.notify.Notification.Severity;
import com.botwithus.bot.cli.gui.runners.CrashText;
import com.botwithus.bot.cli.report.ReportSubject;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.rpc.ReconnectController;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Turns {@link HostEvent}s into toasts. Subscribed to the {@link HostEventBus},
 * which exists whether or not a client is connected, so a JAR that fails to
 * load with no client open still raises its toast, and each event raises one
 * toast however many clients are connected.
 *
 * <p>Reads the Settings page's switches ({@code notify.<kind>.enabled}) and
 * duration ({@code notify.durationS}) for every toast, so a change applies to
 * the next one. Errors stay until closed; everything else stays for the
 * duration.</p>
 *
 * <p>Runs on the host event thread. It keeps no state of its own: the one-per-
 * client rule for connection toasts is applied by the {@link ToastSink}.</p>
 */
public final class ToastFeed implements Consumer<HostEvent> {

    /** What the feed needs to know about a client to word its toast. */
    public interface Clients {

        /** The name the client shows, if the host knows one. */
        Optional<String> nameOf(ClientKey key);

        /** Whether the client's game has exited, so its pipe can never come back. */
        boolean isGone(ClientRef client);
    }

    private static final long MS_PER_SECOND = 1000L;
    private static final long MS_PER_MINUTE = 60_000L;
    /** Below this a wait is shown to a tenth of a second ("0.5 s"); above it, whole seconds. */
    private static final long TENTHS_BELOW_MS = 10_000L;
    private static final double MS_PER_SECOND_D = 1000d;

    private final ToastSink sink;
    private final HostSettings settings;
    private final Clients clients;

    /**
     * @param sink     where toasts go, usually the {@link NotificationOverlay}
     * @param settings read for each toast's switch and duration
     * @param clients  names clients and says whether one's game has exited
     */
    public ToastFeed(ToastSink sink, HostSettings settings, Clients clients) {
        this.sink = sink;
        this.settings = settings;
        this.clients = clients;
    }

    /** Subscribes a new feed to {@code bus}; returns what unsubscribes it. */
    public static Runnable subscribe(HostEventBus bus, ToastSink sink, HostSettings settings, Clients clients) {
        return bus.subscribe(new ToastFeed(sink, settings, clients));
    }

    @Override
    public void accept(HostEvent event) {
        switch (event) {
            case ConnectionLost e -> post(Kind.CONNECTION_LOST, who(e.client()) + " not responding",
                    "Its connection dropped.", e.client(), true);
            case ReconnectStateChanged e -> onReconnectState(e.client(), e.state());
            case ClientResumed e -> onResumed(e);
            case ScriptStalled e -> post(Kind.SCRIPT_STALLED, e.script() + " stalled on " + who(e.client()),
                    "Still inside one loop after " + span(settings.get(SettingKeys.STALL_AFTER_MS)) + ".",
                    e.client(), true);
            case ScriptCrashed e -> onCrashed(e);
            case ScriptLoadFailed e -> onLoadFailed(e);
            case ClientForgotten e -> sink.withdraw(e.client().key());
            // Disconnected on purpose: whatever its connection toast said is moot.
            case ClientClosed e when e.cause() == CloseCause.DISCONNECTED -> sink.withdraw(e.client().key());
            case ClientOpened _, ClientIdentified _, ClientClosed _, ScriptStarted _, ScriptStopped _,
                 ManagementAction _, ManagementScriptCrashed _ -> { }
        }
    }

    // ── Connection ──────────────────────────────────────────────────────────

    private void onReconnectState(ClientRef client, ReconnectState state) {
        String who = who(client);
        switch (state) {
            case ReconnectState.Connected _ ->
                    post(Kind.RECONNECTED, who + " is back", "Reconnected on the same pipe.", client, true);
            case ReconnectState.Reconnecting r -> post(Kind.RECONNECTING, who + " not responding",
                    "Retrying: attempt " + r.attempt() + ", next in " + span(r.nextDelayMs()) + ".",
                    client, r.attempt() == 1);
            case ReconnectState.GivingUp g -> onGaveUp(client, g);
            case ReconnectState.Disconnected _ -> {
                // ConnectionLost, published just before, already says it.
            }
        }
    }

    /**
     * A recovery ended. Stopped by the user: nothing to report, and the retry
     * toast is out of date. The game exited: it closed, and retrying cannot help.
     * Otherwise the attempts ran out, which the user has to act on.
     */
    private void onGaveUp(ClientRef client, ReconnectState.GivingUp gaveUp) {
        if (ReconnectController.wasStoppedOnRequest(gaveUp)) {
            sink.withdraw(client.key());
        } else if (clients.isGone(client)) {
            post(Kind.CLIENT_CLOSED, who(client) + "'s game client closed", client.key().isRemembered()
                    ? "Its card stays until the account is back, or you forget it."
                    : "Its game exited.", client, true);
        } else {
            post(Kind.GAVE_UP, "Gave up reconnecting to " + who(client),
                    gaveUp.attempts() + (gaveUp.attempts() == 1 ? " attempt" : " attempts") + " failed.",
                    client, true);
        }
    }

    /**
     * The same account on a new pipe. One remembered from an earlier run of the
     * host has no previous pipe: that is the host starting, not the client
     * coming back, and every remembered client would toast at once.
     */
    private void onResumed(ClientResumed e) {
        if (e.previousPipe().isPresent()) {
            post(Kind.CLIENT_RESUMED, who(e.client()) + " is back", "Same account, new pipe.", e.client(), true);
        }
    }

    // ── Scripts ─────────────────────────────────────────────────────────────

    /**
     * A crash, with "Send report to script author" when the host knows which
     * connection it was on: run logs are keyed by that connection.
     */
    private void onCrashed(ScriptCrashed e) {
        Optional<ReportSubject> report = e.client().hasPipe()
                ? Optional.of(new ReportSubject(e.client().pipe(), e.script()))
                : Optional.empty();
        post(Kind.SCRIPT_CRASHED, e.script() + " crashed", who(e.client()) + " · " + CrashText.summary(e.crash()),
                Optional.of(e.client().key()), true, report);
    }

    private void onLoadFailed(ScriptLoadFailed e) {
        String reason = e.cause().getMessage() != null
                ? e.cause().getMessage()
                : e.cause().getClass().getSimpleName();
        post(Kind.LOAD_FAILED, "A script JAR failed to load", e.jar().getFileName() + " · " + reason,
                Optional.empty(), true);
    }

    // ── Posting ─────────────────────────────────────────────────────────────

    private void post(Kind kind, String title, String message, ClientRef client, boolean canOpen) {
        post(kind, title, message, Optional.of(client.key()), canOpen);
    }

    /**
     * Posts unless the kind's switch is off. Errors stay until closed; the rest
     * for the set duration. A connection toast that is switched off still takes
     * down the one it would have replaced, which it has made out of date.
     */
    private void post(Kind kind, String title, String message, Optional<ClientKey> client, boolean canOpen) {
        post(kind, title, message, client, canOpen, Optional.empty());
    }

    /** As above, offering a report of {@code report} on the toast. */
    private void post(Kind kind, String title, String message, Optional<ClientKey> client, boolean canOpen,
                      Optional<ReportSubject> report) {
        if (!settings.get(SettingKeys.notifyEnabled(kind.setting()))) {
            if (kind.isConnectionState()) {
                client.ifPresent(sink::withdraw);
            }
            return;
        }
        Optional<Duration> lifetime = kind.severity() == Severity.ERROR
                ? Optional.empty()
                : Optional.of(Duration.ofSeconds(settings.get(SettingKeys.NOTIFY_DURATION_S)));
        sink.post(new Toast(kind, title, message, client, lifetime, canOpen, report));
    }

    /** The client's name, else its pipe, else its key. */
    private String who(ClientRef client) {
        return clients.nameOf(client.key()).orElse(client.hasPipe() ? client.pipe() : client.key().toString());
    }

    /**
     * A span as the design words it: "0.5 s" under ten seconds, whole seconds
     * above, and whole minutes when it is a whole number of them ("10 min").
     */
    private static String span(long ms) {
        if (ms >= MS_PER_MINUTE && ms % MS_PER_MINUTE == 0) {
            return ms / MS_PER_MINUTE + " min";
        }
        if (ms % MS_PER_SECOND == 0) {
            return ms / MS_PER_SECOND + " s";
        }
        if (ms < TENTHS_BELOW_MS) {
            return String.format(Locale.ROOT, "%.1f s", ms / MS_PER_SECOND_D);
        }
        return Math.round(ms / MS_PER_SECOND_D) + " s";
    }
}
