package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.notify.Notification.Action;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;

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
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The toasts in the top-right corner. {@link ToastFeed} decides what to say and
 * posts it here from the host event thread; this overlay takes the posts in on
 * the render thread, once per frame, and draws them.
 *
 * <p>Toasts stack under the top bar on the right, newest first, at most three:
 * a fourth pushes the oldest out. Each slides in, shows a life bar draining over
 * its lifetime, and slides out; one with no lifetime (an error) stays until its
 * close button is pressed.</p>
 *
 * <p>A client has at most one toast about its connection. A newer one takes its
 * place, so a drop, its retries and its recovery read as one toast changing
 * rather than a stack of them; a retry updates a "not responding" toast in place,
 * without sliding in again.</p>
 */
public final class NotificationOverlay implements ToastSink {

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

    /** A post or a withdrawal, waiting for the render thread. */
    private sealed interface Inbox {
        record Post(Toast toast) implements Inbox { }
        record Withdraw(ClientKey client) implements Inbox { }
    }

    private final Queue<Inbox> inbox = new ConcurrentLinkedQueue<>();
    /** On screen, oldest first. Touched on the render thread only, via {@link #update}. */
    private final List<Notification> active = new ArrayList<>();
    private final Clock clock;

    public NotificationOverlay(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void post(Toast toast) {
        inbox.add(new Inbox.Post(toast));
    }

    @Override
    public void withdraw(ClientKey client) {
        inbox.add(new Inbox.Withdraw(client));
    }

    /** The toasts on screen, oldest first, as of the last {@link #update}. */
    public List<Notification> active() {
        return List.copyOf(active);
    }

    /**
     * Takes in what was posted since the last call, then drops the toasts that
     * have gone. Called by {@link #render}; exposed so tests need no GL context.
     */
    public void update() {
        Inbox next = inbox.poll();
        while (next != null) {
            switch (next) {
                case Inbox.Post post -> show(post.toast());
                case Inbox.Withdraw withdraw -> connectionToast(Optional.of(withdraw.client()))
                        .ifPresent(i -> dismiss(active.get(i)));
            }
            next = inbox.poll();
        }
        Instant now = clock.instant();
        active.removeIf(n -> n.isExpired(now));
    }

    /**
     * Updates, then draws the toasts. Call once per frame after the main window
     * so they float above it.
     *
     * @param top      screen y the stack starts at (just under the top bar)
     * @param onAction runs a toast's action button; the toast is then dismissed
     */
    public void render(Controls ui, float top, Consumer<Notification> onAction) {
        update();
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
        List<Notification> out = new ArrayList<>(active.reversed());
        return out.subList(0, Math.min(out.size(), MAX_VISIBLE));
    }

    // ── Taking posts in ────────────────────────────────────────────────────

    /**
     * Shows {@code toast}: in place of the client's connection toast if it is
     * one and there is one, else as a new toast if it may open one.
     */
    private void show(Toast toast) {
        Optional<Integer> same = toast.kind().isConnectionState()
                ? connectionToast(toast.client())
                : Optional.empty();
        if (same.isEmpty()) {
            if (toast.canOpen()) {
                add(fresh(toast));
            }
            return;
        }
        int at = same.get();
        Notification old = active.get(at);
        if (old.kind().isOutage() && toast.kind().isOutage()) {
            active.set(at, new Notification(old.id(), toast.kind(), toast.title(), toast.message(),
                    toast.client(), old.createdAt(), old.expiresAt()));
        } else {
            active.remove(at);
            add(fresh(toast));
        }
    }

    private Notification fresh(Toast toast) {
        Instant now = clock.instant();
        return new Notification(UUID.randomUUID(), toast.kind(), toast.title(), toast.message(), toast.client(),
                now, toast.lifetime().map(now::plus));
    }

    /** Adds {@code n} as the newest toast, pushing the oldest out past {@link #MAX_VISIBLE}. */
    private void add(Notification n) {
        active.add(n);
        while (active.size() > MAX_VISIBLE) {
            active.removeFirst();
        }
    }

    /** Where the toast about {@code client}'s connection is, if one is on screen. */
    private Optional<Integer> connectionToast(Optional<ClientKey> client) {
        for (int i = 0; i < active.size(); i++) {
            Notification n = active.get(i);
            if (n.kind().isConnectionState() && n.client().equals(client)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
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
        if (n.kind().action() != Action.NONE) {
            h += m.u(1.5f) + m.controlSmallHeight();
        }
        return h;
    }

    private float drawToast(Controls ui, Notification n, float x0, float y, float width,
                            Consumer<Notification> onAction) {
        ImGuiTheme.Metrics m = ui.m();
        float h = height(ui, n, width);
        float in = ui.motion().ease(progress(n.createdAt(), ImGuiTheme.DURATION_S));
        float out = n.expiresAt()
                .map(end -> ui.motion().ease(progress(end.minus(slide()), ImGuiTheme.DURATION_S)))
                .orElse(0f);
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
        String action = n.kind().action().label();
        if (n.kind().action() != Action.NONE) {
            float tx = x + pad + ui.fonts().body().getFontSize() * ICON_COL_EM + m.u(2);
            ImGui.setCursorScreenPos(tx, y + h - pad - m.controlSmallHeight());
            if (ui.button("##toast-a-" + n.id(), null, action, Tone.GHOST, true, m.controlSmallHeight())) {
                onAction.accept(n);
                dismiss(n);
            }
        }
    }

    /** The time left, drained along the bottom edge. A toast that stays until closed has none. */
    private void drawLifeBar(ImDrawList draw, Notification n, float x, float bottom, float width, float r, int col) {
        if (n.expiresAt().isEmpty()) {
            return;
        }
        float left = 1f - progress(n.createdAt(), secondsBetween(n.createdAt(), n.expiresAt().get()));
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

    /** How long a toast takes to slide in or out. */
    private static Duration slide() {
        return Duration.ofMillis((long) (ImGuiTheme.DURATION_S * MILLIS_PER_SECOND));
    }

    /** Starts the slide-out now by pulling the expiry in to one slide's length. */
    private void dismiss(Notification n) {
        Instant soon = clock.instant().plus(slide());
        int at = active.indexOf(n);
        boolean isLeavingLater = n.expiresAt().map(soon::isBefore).orElse(true);
        if (at >= 0 && isLeavingLater) {
            active.set(at, new Notification(n.id(), n.kind(), n.title(), n.message(), n.client(),
                    n.createdAt(), Optional.of(soon)));
        }
    }

    private static String iconOf(Kind kind) {
        return switch (kind) {
            case CONNECTION_LOST -> Icons.WARNING;
            case RECONNECTING -> Icons.SPINNER;
            case GAVE_UP -> Icons.PLUG_XMARK;
            case CLIENT_CLOSED -> Icons.POWER;
            case RECONNECTED, CLIENT_RESUMED -> Icons.LINK;
            case SCRIPT_STALLED -> Icons.HOURGLASS;
            case SCRIPT_CRASHED -> Icons.CIRCLE_XMARK;
            case LOAD_FAILED -> Icons.FILE_XMARK;
        };
    }

    private static int colorOf(Kind kind) {
        return switch (kind) {
            case CONNECTION_LOST, RECONNECTING, SCRIPT_STALLED -> ImGuiTheme.COL_WARN;
            case RECONNECTED, CLIENT_RESUMED -> ImGuiTheme.COL_ACCENT;
            case CLIENT_CLOSED -> ImGuiTheme.COL_FG2;
            case GAVE_UP, SCRIPT_CRASHED, LOAD_FAILED -> ImGuiTheme.COL_DANGER;
        };
    }
}
