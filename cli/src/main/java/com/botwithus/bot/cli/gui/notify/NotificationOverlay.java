package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;
import com.botwithus.bot.cli.gui.notify.Notification.Severity;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Toasts for the six failure and recovery events: connection lost, reconnecting,
 * reconnected, gave up, script crashed and script JAR failed to load. Pure
 * {@link EventBus}-driven — no Logback appender, no polling.
 *
 * <p>Toasts stack under the top bar on the right, newest first, at most three.
 * Each slides in, shows a life bar draining over {@link #DEFAULT_TTL}, and slides
 * out. The queue is an <em>instance</em> field, not static — one overlay per app.</p>
 *
 * <p>{@link #subscribeTo} wires the four event types this overlay cares about.
 * Bus subscriptions retain the lambdas, so re-subscribing on a fresh bus is the
 * supported way to attach a new connection.</p>
 */
public final class NotificationOverlay {

    static final Duration DEFAULT_TTL = Duration.ofSeconds((long) ImGuiTheme.TOAST_LIFE_S);
    private static final int MAX_VISIBLE = 3;
    private static final float WIDTH_EM = 20f;
    private static final float ICON_COL_EM = 1.333f;
    private static final float SLIDE_EM = 1.067f;
    private static final float LIFE_BAR_PX = 2f;
    private static final float LIFE_ALPHA = 0.5f;
    private static final float LINE = 1.35f;
    private static final float SHADOW_EM = 0.267f;
    private static final float SPINNER_STROKE_PX = 2f;
    private static final float SPINNER_PERIOD_S = 0.9f;
    private static final float SPINNER_SWEEP = (float) (Math.PI / 2);
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final float SECONDS_PER_MILLI = 0.001f;
    private static final float SPINNER_TRACK_ALPHA = 0.2f;

    private final Deque<Notification> active = new ConcurrentLinkedDeque<>();
    private final Clock clock;
    private final Function<String, String> accountOf;

    public NotificationOverlay() {
        this(Clock.systemDefaultZone());
    }

    public NotificationOverlay(Clock clock) {
        this(clock, name -> name);
    }

    /**
     * @param accountOf maps a connection name to the account playing on it, so a
     *                  toast can say who; returns the name itself when unknown
     */
    public NotificationOverlay(Clock clock, Function<String, String> accountOf) {
        this.clock = clock;
        this.accountOf = accountOf;
    }

    /** Visible-notifications snapshot — exposed for tests. */
    public Collection<Notification> active() {
        return active;
    }

    /**
     * Wires this overlay's event handlers onto the supplied bus. Safe to call
     * multiple times (each call adds new listeners) — typically invoked once
     * per connection's event bus.
     */
    public void subscribeTo(EventBus bus) {
        bus.subscribe(ConnectionLostEvent.class, this::onConnectionLost);
        bus.subscribe(ReconnectStateChangedEvent.class, this::onReconnectStateChanged);
        bus.subscribe(ScriptCrashedEvent.class, this::onScriptCrashed);
        bus.subscribe(ScriptLoadFailedEvent.class, this::onScriptLoadFailed);
    }

    /** Culls expired toasts. Called by {@link #render}; exposed so tests need no GL context. */
    public void cull() {
        Instant now = clock.instant();
        active.removeIf(n -> n.isExpired(now));
    }

    /**
     * Culls, then draws the toasts. Call once per frame after the main window so
     * they float above it.
     *
     * @param top      screen y the stack starts at (just under the top bar)
     * @param onAction runs a toast's action button; the toast is then dismissed
     */
    public void render(Controls ui, float top, Consumer<Notification> onAction) {
        cull();
        if (active.isEmpty()) {
            return;
        }
        List<Notification> visible = newestFirst();
        ImGuiTheme.Metrics m = ui.m();
        var vp = ImGui.getMainViewport();
        float width = Math.min(ui.fonts().body().getFontSize() * WIDTH_EM, vp.getSizeX() - m.u(3) * 2f);
        float x = vp.getPosX() + vp.getSizeX() - m.u(3) - width;
        float total = -m.u(2);
        for (Notification n : visible) {
            total += height(ui, n, width) + m.u(2);
        }
        beginStackWindow(x, top, width, total);
        float y = top;
        for (Notification n : visible) {
            y += drawToast(ui, n, x, y, width, onAction) + m.u(2);
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    private List<Notification> newestFirst() {
        List<Notification> all = new ArrayList<>(active);
        List<Notification> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < MAX_VISIBLE; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    private static void beginStackWindow(float x, float y, float w, float h) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, h);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        int flags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoBackground
                | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoFocusOnAppearing
                | ImGuiWindowFlags.NoNav | ImGuiWindowFlags.NoMove;
        ImGui.begin("##toasts", flags);
    }

    // ── One toast ──────────────────────────────────────────────────────────

    private float bodyWidth(Controls ui, float width) {
        return width - ui.m().u(3) * 2f - ui.fonts().body().getFontSize() * ICON_COL_EM - ui.m().u(2);
    }

    private ImFont bodyFont(Controls ui, Notification n) {
        return n.kind().isMonoBody() ? ui.fonts().monoCaption() : ui.fonts().caption();
    }

    private float height(Controls ui, Notification n, float width) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont body = bodyFont(ui, n);
        int lines = ui.wrap(body, n.message(), bodyWidth(ui, width)).size();
        float h = m.u(3) * 2f + ui.fonts().small().getFontSize() * LINE + lines * body.getFontSize() * LINE;
        if (n.kind().actionLabel() != null) {
            h += m.u(1.5f) + m.controlSmallHeight();
        }
        return h;
    }

    private float drawToast(Controls ui, Notification n, float x0, float y, float width,
                            Consumer<Notification> onAction) {
        ImGuiTheme.Metrics m = ui.m();
        float h = height(ui, n, width);
        float in = Motion.easeOutCubic(progress(n.createdAt(), ImGuiTheme.DURATION_S));
        float out = Motion.easeOutCubic(progress(n.expiresAt().minusMillis(
                (long) (ImGuiTheme.DURATION_S * MILLIS_PER_SECOND)), ImGuiTheme.DURATION_S));
        float alpha = in * (1f - out);
        float x = x0 + (1f - alpha) * ui.fonts().body().getFontSize() * SLIDE_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        float r = m.radiusXl();
        float shadow = ui.fonts().body().getFontSize() * SHADOW_EM;
        draw.addRectFilled(x, y + shadow, x + width, y + h, Controls.scaleAlpha(ImGuiTheme.COL_SHADOW, alpha), r);
        draw.addRectFilled(x, y, x + width, y + h, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, alpha), r);
        draw.addRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + h - 0.5f,
                Controls.scaleAlpha(ImGuiTheme.COL_BORDER, alpha), r);
        int accent = colorOf(n.kind());
        drawContent(ui, draw, n, x, y, width, alpha, accent);
        drawLifeBar(draw, n, x, y + h, width, r, Controls.scaleAlpha(accent, LIFE_ALPHA * alpha));
        drawButtons(ui, n, x, y, width, h, onAction);
        return h;
    }

    private void drawContent(Controls ui, ImDrawList draw, Notification n, float x, float y, float width,
                             float alpha, int accent) {
        ImGuiTheme.Metrics m = ui.m();
        float pad = m.u(3);
        ImFont title = ui.fonts().smallMedium();
        float lineH = ui.fonts().small().getFontSize() * LINE;
        float iconX = x + pad;
        if (n.kind() == Kind.RECONNECTING) {
            drawSpinner(draw, iconX + ui.fonts().caption().getFontSize() * 0.5f, y + pad + lineH * 0.5f,
                    ui.fonts().caption().getFontSize() * 0.5f, Controls.scaleAlpha(accent, alpha));
        } else {
            ui.textCentredY(draw, ui.fonts().small(), iconX, y + pad, lineH, Controls.scaleAlpha(accent, alpha),
                    iconOf(n.kind()));
        }
        float tx = x + pad + ui.fonts().body().getFontSize() * ICON_COL_EM + m.u(2);
        float closeW = m.controlSmallHeight();
        ui.textCentredY(draw, title, tx, y + pad, lineH, Controls.scaleAlpha(ImGuiTheme.COL_FG, alpha),
                ui.ellipsize(title, n.title(), x + width - pad - closeW - tx));
        ImFont body = bodyFont(ui, n);
        float by = y + pad + lineH;
        for (String line : ui.wrap(body, n.message(), bodyWidth(ui, width))) {
            ui.text(draw, body, tx, by, Controls.scaleAlpha(ImGuiTheme.COL_FG2, alpha), line);
            by += body.getFontSize() * LINE;
        }
    }

    private void drawButtons(Controls ui, Notification n, float x, float y, float width, float h,
                             Consumer<Notification> onAction) {
        ImGuiTheme.Metrics m = ui.m();
        float pad = m.u(3);
        float closeW = m.controlSmallHeight();
        ImGui.setCursorScreenPos(x + width - pad - closeW + m.u(1), y + pad - m.u(1));
        if (ui.button("##toast-x-" + n.id(), Icons.XMARK, "", Tone.ICON, true, closeW)) {
            dismiss(n);
        }
        String action = n.kind().actionLabel();
        if (action != null) {
            float tx = x + pad + ui.fonts().body().getFontSize() * ICON_COL_EM + m.u(2);
            ImGui.setCursorScreenPos(tx, y + h - pad - m.controlSmallHeight());
            if (ui.button("##toast-a-" + n.id(), null, action, Tone.GHOST, true, m.controlSmallHeight())) {
                onAction.accept(n);
                dismiss(n);
            }
        }
    }

    private void drawLifeBar(ImDrawList draw, Notification n, float x, float bottom, float width, float r, int col) {
        float left = 1f - progress(n.createdAt(), secondsBetween(n.createdAt(), n.expiresAt()));
        if (left <= 0f) {
            return;
        }
        draw.pushClipRect(x, bottom - LIFE_BAR_PX, x + width * left, bottom, true);
        draw.addRectFilled(x, bottom - r * 2f, x + width, bottom, col, r, ImDrawFlags.RoundCornersBottom);
        draw.popClipRect();
    }

    private static void drawSpinner(ImDrawList draw, float cx, float cy, float r, int col) {
        draw.addCircle(cx, cy, r, Controls.scaleAlpha(col, SPINNER_TRACK_ALPHA), 0, SPINNER_STROKE_PX);
        float a0 = (float) ((ImGui.getTime() % SPINNER_PERIOD_S) / SPINNER_PERIOD_S * Math.PI * 2);
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, a0, a0 + SPINNER_SWEEP);
        draw.pathStroke(col, 0, SPINNER_STROKE_PX);
    }

    private float progress(Instant from, float spanSeconds) {
        float elapsed = secondsBetween(from, clock.instant());
        return spanSeconds <= 0f ? 1f : Math.max(0f, Math.min(1f, elapsed / spanSeconds));
    }

    private static float secondsBetween(Instant a, Instant b) {
        return Duration.between(a, b).toMillis() * SECONDS_PER_MILLI;
    }

    /** Starts the slide-out now by pulling the expiry in to one slide's length. */
    private void dismiss(Notification n) {
        Instant soon = clock.instant().plusMillis((long) (ImGuiTheme.DURATION_S * MILLIS_PER_SECOND));
        if (soon.isBefore(n.expiresAt()) && active.remove(n)) {
            active.addLast(new Notification(n.id(), n.kind(), n.severity(), n.title(), n.message(),
                    n.subject(), n.createdAt(), soon));
        }
    }

    private static String iconOf(Kind kind) {
        return switch (kind) {
            case CONNECTION_LOST -> Icons.LINK_SLASH;
            case RECONNECTING -> Icons.SPINNER;
            case RECONNECTED -> Icons.CIRCLE_CHECK;
            case GAVE_UP -> Icons.PLUG_XMARK;
            case SCRIPT_CRASHED -> Icons.WARNING;
            case LOAD_FAILED -> Icons.FILE_XMARK;
        };
    }

    private static int colorOf(Kind kind) {
        return switch (kind) {
            case RECONNECTING -> ImGuiTheme.COL_WARN;
            case RECONNECTED -> ImGuiTheme.COL_ACCENT;
            case CONNECTION_LOST, GAVE_UP, SCRIPT_CRASHED, LOAD_FAILED -> ImGuiTheme.COL_DANGER;
        };
    }

    // ── Event handlers ──────────────────────────────────────────────────────

    private void onConnectionLost(ConnectionLostEvent ev) {
        String who = accountOf.apply(ev.connectionName());
        String body = who.equals(ev.connectionName())
                ? ev.connectionName() + " stopped responding."
                : who + " stopped responding on " + ev.connectionName() + ".";
        push(Kind.CONNECTION_LOST, Severity.ERROR, "Connection lost", body, ev.connectionName());
    }

    private void onReconnectStateChanged(ReconnectStateChangedEvent ev) {
        String who = accountOf.apply(ev.connectionName());
        switch (ev.state()) {
            case ReconnectState.Connected c ->
                    push(Kind.RECONNECTED, Severity.INFO, "Reconnected", who + " is back.", ev.connectionName());
            case ReconnectState.Reconnecting r -> push(Kind.RECONNECTING, Severity.WARN, "Reconnecting",
                    who + ": attempt " + r.attempt() + ", next try in "
                            + Math.max(1L, Math.round(r.nextDelayMs() / (double) MILLIS_PER_SECOND)) + " s.",
                    ev.connectionName());
            case ReconnectState.GivingUp g -> push(Kind.GAVE_UP, Severity.ERROR, "Gave up reconnecting",
                    who + ": " + g.attempts() + (g.attempts() == 1 ? " attempt" : " attempts") + " failed.",
                    ev.connectionName());
            case ReconnectState.Disconnected d -> {
                // Disconnected itself is covered by ConnectionLostEvent — skip
                // to avoid double-notifying.
            }
        }
    }

    private void onScriptCrashed(ScriptCrashedEvent ev) {
        String where = ev.connectionName() != null ? accountOf.apply(ev.connectionName()) + " · " : "";
        String cause = ev.crash().cause() != null ? ev.crash().cause().getClass().getSimpleName() : "Error";
        push(Kind.SCRIPT_CRASHED, Severity.ERROR, ev.scriptName() + " crashed",
                where + cause + " in " + phaseMethod(ev.crash().phase()), ev.connectionName());
    }

    private static String phaseMethod(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart()";
            case ON_LOOP -> "onLoop()";
            case ON_STOP -> "onStop()";
            case ON_CONFIG_UPDATE -> "onConfigUpdate()";
        };
    }

    private void onScriptLoadFailed(ScriptLoadFailedEvent ev) {
        String reason = ev.cause().getMessage() != null
                ? ev.cause().getMessage()
                : ev.cause().getClass().getSimpleName();
        push(Kind.LOAD_FAILED, Severity.WARN, "JAR failed to load",
                ev.jar().getFileName() + " · " + reason, ev.jar().toString());
    }

    private void push(Kind kind, Severity severity, String title, String message, String subject) {
        Instant now = clock.instant();
        active.addLast(new Notification(UUID.randomUUID(), kind, severity, title,
                message != null ? message : "", subject, now, now.plus(DEFAULT_TTL)));
    }
}
