package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.api.event.ConnectionLostEvent;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.ReconnectStateChangedEvent;
import com.botwithus.bot.api.event.ScriptCrashedEvent;
import com.botwithus.bot.api.event.ScriptLoadFailedEvent;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Fixed-position overlay rendering transient banners for failure events
 * (connection drops, reconnect transitions, script crashes, script-load
 * failures). Pure {@link EventBus}-driven — no Logback appender, no polling.
 *
 * <p>Each notification has a TTL (default {@link #DEFAULT_TTL}); expired
 * banners are culled on the next {@link #render} pass. The notification
 * queue is an <em>instance</em> field, not static — one overlay per app.
 *
 * <p>{@link #subscribeTo} wires the four event types this overlay cares
 * about. Bus subscriptions retain the lambdas, so re-subscribing on a fresh
 * bus is the supported way to attach a new connection.</p>
 */
public final class NotificationOverlay {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(6);
    private static final int MAX_VISIBLE = 5;
    private static final float OVERLAY_WIDTH = 360f;
    private static final float OVERLAY_MARGIN = 12f;
    private static final float ROW_HEIGHT = 56f;
    private static final float ROW_GAP = 6f;

    private final Deque<Notification> active = new ConcurrentLinkedDeque<>();
    private final Clock clock;

    public NotificationOverlay() {
        this(Clock.systemDefaultZone());
    }

    public NotificationOverlay(Clock clock) {
        this.clock = clock;
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

    /**
     * Renders the overlay. Should be called once per frame near the end of
     * the app's frame loop, after the main window content has drawn so the
     * banners float above everything else.
     */
    public void render() {
        Instant now = clock.instant();
        cullExpired(now);
        if (active.isEmpty()) {
            return;
        }
        renderBanners();
    }

    private void cullExpired(Instant now) {
        active.removeIf(n -> n.isExpired(now));
    }

    private void renderBanners() {
        var viewport = ImGui.getMainViewport();
        float vpX = viewport.getPosX();
        float vpY = viewport.getPosY();
        float vpW = viewport.getSizeX();

        float x = vpX + vpW - OVERLAY_WIDTH - OVERLAY_MARGIN;
        float y = vpY + OVERLAY_MARGIN;

        ImDrawList draw = ImGui.getForegroundDrawList();
        int idx = 0;
        for (Notification n : active) {
            if (idx >= MAX_VISIBLE) {
                break;
            }
            drawBanner(draw, n, x, y);
            y += ROW_HEIGHT + ROW_GAP;
            idx++;
        }
    }

    private static void drawBanner(ImDrawList draw, Notification n, float x, float y) {
        int bg = ImGuiTheme.imCol32(
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 0.95f);
        int border = severityBorder(n.severity());
        int accent = severityAccent(n.severity());
        String icon = severityIcon(n.severity());

        draw.addRectFilled(x, y, x + OVERLAY_WIDTH, y + ROW_HEIGHT, bg, 6f);
        draw.addRect(x, y, x + OVERLAY_WIDTH, y + ROW_HEIGHT, border, 6f);
        // Left accent bar
        draw.addRectFilled(x, y + 2f, x + 3f, y + ROW_HEIGHT - 2f, accent, 2f);

        int titleCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.95f);
        int msgCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.85f);

        draw.addText(x + 14f, y + 8f, accent, icon);
        ImVec2 iconSize = new ImVec2();
        ImGui.calcTextSize(iconSize, icon);
        draw.addText(x + 14f + iconSize.x + 8f, y + 8f, titleCol, n.title());
        draw.addText(x + 14f, y + 8f + ImGui.getTextLineHeightWithSpacing(),
                msgCol, truncate(n.message(), 60));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static int severityBorder(Notification.Severity sev) {
        return switch (sev) {
            case INFO -> ImGuiTheme.imCol32(ImGuiTheme.BLUE_R, ImGuiTheme.BLUE_G, ImGuiTheme.BLUE_B, 0.45f);
            case WARN -> ImGuiTheme.imCol32(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 0.55f);
            case ERROR -> ImGuiTheme.imCol32(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.65f);
        };
    }

    private static int severityAccent(Notification.Severity sev) {
        return switch (sev) {
            case INFO -> ImGuiTheme.imCol32(ImGuiTheme.BLUE_R, ImGuiTheme.BLUE_G, ImGuiTheme.BLUE_B, 1f);
            case WARN -> ImGuiTheme.imCol32(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f);
            case ERROR -> ImGuiTheme.imCol32(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f);
        };
    }

    private static String severityIcon(Notification.Severity sev) {
        return switch (sev) {
            case INFO -> Icons.INFO;
            case WARN -> Icons.WARNING;
            case ERROR -> Icons.WARNING;
        };
    }

    // ── Event handlers ──────────────────────────────────────────────────────

    private void onConnectionLost(ConnectionLostEvent ev) {
        String msg = ev.cause() != null
                ? ev.cause().getClass().getSimpleName() + ": " + ev.cause().getMessage()
                : "Pipe closed";
        push(Notification.Severity.ERROR, "Connection lost: " + ev.connectionName(), msg);
    }

    private void onReconnectStateChanged(ReconnectStateChangedEvent ev) {
        switch (ev.state()) {
            case ReconnectState.Connected c ->
                    push(Notification.Severity.INFO, "Reconnected", ev.connectionName());
            case ReconnectState.Reconnecting r ->
                    push(Notification.Severity.WARN, "Reconnecting " + ev.connectionName(),
                            "attempt " + r.attempt() + " in " + r.nextDelayMs() + "ms");
            case ReconnectState.GivingUp g ->
                    push(Notification.Severity.ERROR, "Giving up on " + ev.connectionName(),
                            "after " + g.attempts() + " attempts");
            case ReconnectState.Disconnected d -> {
                // Disconnected itself is covered by ConnectionLostEvent — skip
                // to avoid double-notifying.
            }
        }
    }

    private void onScriptCrashed(ScriptCrashedEvent ev) {
        push(Notification.Severity.ERROR,
                "Script crashed: " + ev.scriptName(),
                ev.crash().phase() + " — " + ev.crash().cause().getClass().getSimpleName());
    }

    private void onScriptLoadFailed(ScriptLoadFailedEvent ev) {
        push(Notification.Severity.WARN,
                "Failed to load " + ev.jar().getFileName(),
                ev.cause().getClass().getSimpleName() + ": " + ev.cause().getMessage());
    }

    private void push(Notification.Severity severity, String title, String message) {
        Notification n = new Notification(UUID.randomUUID(), severity, title,
                message != null ? message : "",
                clock.instant().plus(DEFAULT_TTL));
        active.addLast(n);
    }
}
