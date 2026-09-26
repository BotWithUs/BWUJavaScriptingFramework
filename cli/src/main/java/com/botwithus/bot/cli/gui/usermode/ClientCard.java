package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.gui.usermode.board.ClientStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;

/**
 * One client card: account and pipe, a status chip, an inset with the script
 * and its pulse lane, and the actions that make sense for the card's state.
 *
 * <p>Every state draws the same height, so a grid of mixed states lines up.
 * Actions that change the client go straight to {@link ClientActions}; the two
 * that open something (the picker, the inspector) are handed back as an
 * {@link Intent} for the page to act on.</p>
 */
final class ClientCard {

    /** What the page must do after a card was clicked. */
    enum Intent { NONE, SELECT, START_SCRIPT, CONFIGURE }

    private static final float ACCOUNT_LINE = 1.25f;
    private static final float PIPE_LINE = 1.4f;
    private static final float NAME_LINE = 1.25f;
    private static final float META_LINE = 1.35f;
    private static final float SKELETON_EM = 0.667f;
    private static final float SKELETON_WIDE = 0.7f;
    private static final float SKELETON_NARROW = 0.45f;
    private static final float SKELETON_DIM = 0.45f;
    private static final float SPINNER_EM = 0.933f;
    private static final float SPINNER_PERIOD_S = 0.9f;
    private static final float SPINNER_SWEEP = (float) (Math.PI / 2);
    private static final float SPINNER_STROKE_PX = 2f;
    private static final float BEAT_PERIOD_S = 2f;
    private static final float BEAT_CENTER = 0.85f;
    private static final float BEAT_HALF_WIDTH = 0.15f;
    private static final float BEAT_LOW = 0.35f;
    private static final float APPEAR_SLIDE_EM = 0.267f;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long SECONDS_PER_MINUTE = 60L;

    private final Controls ui;
    private final PulseLaneView lane;

    ClientCard(Controls ui) {
        this.ui = ui;
        this.lane = new PulseLaneView(ui);
    }

    /** Height of every card at the current scale. */
    float height() {
        ImGuiTheme.Metrics m = ui.m();
        return m.u(3) * 2f + headHeight() + m.u(3) + insetHeight() + m.u(3) + m.controlHeight();
    }

    private float headHeight() {
        return ui.fonts().body().getFontSize() * ACCOUNT_LINE + ui.fonts().monoCaption().getFontSize() * PIPE_LINE;
    }

    private float rowHeight() {
        float text = ui.fonts().body().getFontSize() * NAME_LINE + ui.fonts().small().getFontSize() * META_LINE;
        return Math.max(ui.m().iconTile(), text);
    }

    private float insetHeight() {
        ImGuiTheme.Metrics m = ui.m();
        float natural = m.u(3) * 2f + rowHeight() + m.u(2) + m.laneHeight() + m.hairline() * 2f;
        return Math.max(m.insetMinHeight(), natural);
    }

    /**
     * Draws the card with its top-left at (x, y).
     *
     * @param appear 0..1 entrance progress; below 1 the card fades and slides in
     */
    Intent render(ClientView view, float x, float y, float w, boolean selected, float appear,
                  ClientActions actions) {
        float slide = (1f - Motion.easeOutCubic(appear)) * ui.fonts().body().getFontSize() * APPEAR_SLIDE_EM;
        float top = y + slide;
        float h = height();
        ImDrawList draw = ImGui.getWindowDrawList();
        boolean hovered = ImGui.isMouseHoveringRect(x, top, x + w, top + h) && ImGui.isWindowHovered();
        paintFrame(draw, view.id(), x, top, w, h, selected, hovered);
        paintHead(draw, view, x, top, w);
        float insetY = top + ui.m().u(3) + headHeight() + ui.m().u(3);
        paintInset(draw, view.status(), x + ui.m().u(4), insetY, w - ui.m().u(4) - ui.m().u(3));
        Intent intent = footer(view, x, top, w, h, actions);
        if (appear < 1f) {
            draw.addRectFilled(x - 1f, top - 1f, x + w + 1f, top + h + 1f,
                    Controls.scaleAlpha(ImGuiTheme.COL_BG, 1f - appear));
        }
        if (intent == Intent.NONE && hovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) {
            return Intent.SELECT;
        }
        return intent;
    }

    // ── Frame + head ───────────────────────────────────────────────────────

    private void paintFrame(ImDrawList draw, String id, float x, float y, float w, float h,
                            boolean selected, boolean hovered) {
        float r = ui.m().radiusLarge();
        float t = Motion.step("card:" + id, hovered ? 1f : 0f, 1f / ImGuiTheme.DURATION_FAST_S);
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_SURFACE, r);
        int border = selected ? ImGuiTheme.COL_ACCENT
                : Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, t);
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, border, r);
    }

    private void paintHead(ImDrawList draw, ClientView view, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        Chip chip = chipFor(view.status());
        float chipW = ui.chipWidth(chip.label());
        float right = x + w - m.u(3);
        float left = x + m.u(4);
        float textW = right - chipW - m.u(2) - left;
        float top = y + m.u(3);
        ImFont account = ui.fonts().bodyMedium();
        ui.text(draw, account, left, top, ImGuiTheme.COL_FG, ui.ellipsize(account, view.account(), textW));
        String pipe = view.world() > 0 ? view.id() + " · W" + view.world() : view.id();
        ImFont mono = ui.fonts().monoCaption();
        float pipeY = top + ui.fonts().body().getFontSize() * ACCOUNT_LINE;
        ui.text(draw, mono, left, pipeY, ImGuiTheme.COL_FG2, ui.ellipsize(mono, pipe, textW));
        ui.chip(draw, right - chipW, top, chip.label(), chip.fg(), chip.bg(), chip.dotAlpha());
    }

    private record Chip(String label, int fg, int bg, float dotAlpha) {}

    private static Chip chipFor(ClientStatus status) {
        return switch (status) {
            case ClientStatus.Running ignored ->
                    new Chip("Running", ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT, beat());
            case ClientStatus.Idle ignored -> new Chip("Idle", ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED, 1f);
            case ClientStatus.Loading ignored -> new Chip("Loading", ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT, 1f);
            case ClientStatus.Lost ignored ->
                    new Chip("Lost contact", ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT, 1f);
            case ClientStatus.Reconnecting ignored ->
                    new Chip("Reconnecting", ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT, 1f);
            case ClientStatus.Crashed ignored ->
                    new Chip("Crashed", ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT, 1f);
        };
    }

    /** The running dot's heartbeat: steady, with one soft dip late in each 2 s cycle. */
    private static float beat() {
        float phase = (float) (ImGui.getTime() % BEAT_PERIOD_S) / BEAT_PERIOD_S;
        float distance = Math.abs(phase - BEAT_CENTER) / BEAT_HALF_WIDTH;
        return distance >= 1f ? 1f : BEAT_LOW + (1f - BEAT_LOW) * distance;
    }

    // ── Inset ──────────────────────────────────────────────────────────────

    private void paintInset(ImDrawList draw, ClientStatus status, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float h = insetHeight();
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_BG, m.radius());
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radius());
        float cx = x + m.u(3);
        float cy = y + m.u(3);
        float cw = w - m.u(3) * 2f;
        float laneY = y + h - m.u(3) - m.laneHeight();
        switch (status) {
            case ClientStatus.Running r -> {
                paintRunningRow(draw, r, cx, cy, cw);
                lane.running(draw, cx, laneY, cw, m.laneHeight(), r.recentLoopNanos(), r.avgLoopMs());
            }
            case ClientStatus.Idle ignored -> {
                paintRow(draw, cx, cy, cw, Row.idle(ui));
                lane.idle(draw, cx, laneY, cw, m.laneHeight());
            }
            case ClientStatus.Loading ignored -> {
                paintSkeletonRow(draw, cx, cy, cw);
                lane.loading(draw, cx, laneY, cw, m.laneHeight());
            }
            case ClientStatus.Lost l -> {
                paintRow(draw, cx, cy, cw, Row.lost(ui, l));
                lane.dead(draw, cx, laneY, cw, m.laneHeight());
            }
            case ClientStatus.Reconnecting r -> {
                paintRow(draw, cx, cy, cw, Row.reconnecting(ui, r));
                paintSpinner(draw, cx, cy);
                lane.dead(draw, cx, laneY, cw, m.laneHeight());
            }
            case ClientStatus.Crashed c -> {
                paintRow(draw, cx, cy, cw, Row.crashed(ui, c));
                lane.dead(draw, cx, laneY, cw, m.laneHeight());
            }
        }
    }

    /**
     * The icon tile, name and meta line of an inset.
     *
     * @param icon  glyph for the tile, or {@code null} to leave the tile empty (spinner drawn over it)
     */
    private record Row(String icon, int iconFg, int iconBg, String name, ImFont nameFont, int nameCol,
                       String meta, ImFont metaFont, int metaCol) {

        static Row idle(Controls ui) {
            return new Row(Icons.PLAY, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                    "No script running", ui.fonts().body(), ImGuiTheme.COL_FG2,
                    "Pick a script to run", ui.fonts().small(), ImGuiTheme.COL_FG2);
        }

        static Row lost(Controls ui, ClientStatus.Lost l) {
            String meta = l.wasRunning() != null ? "Was running " + l.wasRunning().name() : "No script was running";
            return new Row(Icons.LINK_SLASH, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                    "No reply for " + clock(l.silentForMillis()), ui.fonts().bodyMedium(), ImGuiTheme.COL_FG,
                    meta, ui.fonts().small(), ImGuiTheme.COL_FG2);
        }

        static Row reconnecting(Controls ui, ClientStatus.Reconnecting r) {
            long seconds = Math.max(1L, Math.round(r.nextDelayMs() / (double) MILLIS_PER_SECOND));
            String meta = "Attempt " + r.attempt() + " of " + r.maxAttempts() + " · next in " + seconds + " s";
            return new Row(null, ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT,
                    "Reconnecting…", ui.fonts().bodyMedium(), ImGuiTheme.COL_FG,
                    meta, ui.fonts().small(), ImGuiTheme.COL_FG2);
        }

        static Row crashed(Controls ui, ClientStatus.Crashed c) {
            return new Row(Icons.WARNING, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                    c.script().name() + " stopped", ui.fonts().bodyMedium(), ImGuiTheme.COL_FG,
                    c.summary(), ui.fonts().monoCaption(), ImGuiTheme.COL_DANGER);
        }
    }

    private void paintRow(ImDrawList draw, float x, float y, float w, Row row) {
        paintRow(draw, x, y, w, row, 0f);
    }

    private void paintRow(ImDrawList draw, float x, float y, float w, Row row, float rightReserve) {
        ImGuiTheme.Metrics m = ui.m();
        float tile = m.iconTile();
        float rowH = rowHeight();
        float tileY = y + (rowH - tile) * 0.5f;
        if (row.icon() != null) {
            ui.iconTile(draw, x, tileY, tile, row.icon(), row.iconFg(), row.iconBg());
        } else {
            draw.addRectFilled(x, tileY, x + tile, tileY + tile, row.iconBg(), m.radius());
        }
        float tx = x + tile + m.u(3);
        float tw = w - tile - m.u(3) - rightReserve;
        float nameH = ui.fonts().body().getFontSize() * NAME_LINE;
        float metaH = ui.fonts().small().getFontSize() * META_LINE;
        float ty = y + (rowH - nameH - metaH) * 0.5f;
        ui.text(draw, row.nameFont(), tx, ty, row.nameCol(), ui.ellipsize(row.nameFont(), row.name(), tw));
        float metaY = ty + nameH + (metaH - row.metaFont().getFontSize()) * 0.5f;
        ui.text(draw, row.metaFont(), tx, metaY, row.metaCol(), ui.ellipsize(row.metaFont(), row.meta(), tw));
    }

    private void paintRunningRow(ImDrawList draw, ClientStatus.Running r, float x, float y, float w) {
        ScriptInfo s = r.script();
        String ms = r.avgLoopMs() > 0 ? String.valueOf(Math.round(r.avgLoopMs())) : "—";
        String unit = "ms/loop";
        ImFont numFont = ui.fonts().monoSmall();
        ImFont unitFont = ui.fonts().monoCaption();
        float unitGap = ui.m().u(0.5f);
        float msW = ui.width(numFont, ms) + unitGap + ui.width(unitFont, unit);
        CategoryStyle.Style cat = CategoryStyle.of(s.category());
        paintRow(draw, x, y, w, new Row(cat.icon(), ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                s.name(), ui.fonts().bodyMedium(), ImGuiTheme.COL_FG,
                s.byline(), ui.fonts().small(), ImGuiTheme.COL_FG2), msW + ui.m().u(3));
        float rowH = rowHeight();
        float baseY = y + (rowH - numFont.getFontSize()) * 0.5f;
        float mx = x + w - msW;
        ui.text(draw, numFont, mx, baseY, ImGuiTheme.COL_FG, ms);
        float unitY = baseY + numFont.getFontSize() - unitFont.getFontSize();
        ui.text(draw, unitFont, mx + ui.width(numFont, ms) + unitGap, unitY, ImGuiTheme.COL_FG2, unit);
    }

    private void paintSkeletonRow(ImDrawList draw, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float breathe = Motion.pulse(1.0 / ImGuiTheme.PULSE_PERIOD_S);
        int col = Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, 1f - (1f - SKELETON_DIM) * breathe);
        float tile = m.iconTile();
        float rowH = rowHeight();
        float tileY = y + (rowH - tile) * 0.5f;
        draw.addRectFilled(x, tileY, x + tile, tileY + tile, col, m.radius());
        float tx = x + tile + m.u(3);
        float tw = w - tile - m.u(3);
        float barH = ui.fonts().body().getFontSize() * SKELETON_EM;
        float gap = m.u(2);
        float by = y + (rowH - barH * 2f - gap) * 0.5f;
        draw.addRectFilled(tx, by, tx + tw * SKELETON_WIDE, by + barH, col, m.radiusSmall());
        float by2 = by + barH + gap;
        draw.addRectFilled(tx, by2, tx + tw * SKELETON_NARROW, by2 + barH, col, m.radiusSmall());
    }

    private void paintSpinner(ImDrawList draw, float x, float y) {
        float tile = ui.m().iconTile();
        float cx = x + tile * 0.5f;
        float cy = y + rowHeight() * 0.5f;
        float r = ui.fonts().body().getFontSize() * SPINNER_EM * 0.5f - SPINNER_STROKE_PX * 0.5f;
        draw.addCircle(cx, cy, r, ImGuiTheme.COL_WARN_SOFT, 0, SPINNER_STROKE_PX);
        float a0 = (float) ((ImGui.getTime() % SPINNER_PERIOD_S) / SPINNER_PERIOD_S * Math.PI * 2);
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, a0, a0 + SPINNER_SWEEP);
        draw.pathStroke(ImGuiTheme.COL_WARN, 0, SPINNER_STROKE_PX);
    }

    static String clock(long millis) {
        long seconds = Math.max(0L, millis / MILLIS_PER_SECOND);
        long minutes = seconds / SECONDS_PER_MINUTE;
        return minutes + ":" + String.format("%02d", seconds % SECONDS_PER_MINUTE);
    }

    // ── Footer ─────────────────────────────────────────────────────────────

    private record Action(String id, String icon, String label, Tone tone, boolean enabled, Runnable run,
                          Intent intent) {}

    private Intent footer(ClientView view, float x, float y, float w, float h, ClientActions actions) {
        ImGuiTheme.Metrics m = ui.m();
        List<Action> buttons = footerActions(view, actions);
        float total = 0f;
        for (Action a : buttons) {
            total += ui.buttonWidth(a.icon(), a.label(), a.tone());
        }
        total += m.u(2) * Math.max(0, buttons.size() - 1);
        float by = y + h - m.u(3) - m.controlHeight();
        String note = footerNote(view.status());
        if (note != null) {
            ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().caption(), x + m.u(4), by, m.controlHeight(),
                    ImGuiTheme.COL_FG2, note);
        }
        float bx = x + w - m.u(3) - total;
        Intent intent = Intent.NONE;
        for (Action a : buttons) {
            ImGui.setCursorScreenPos(bx, by);
            if (ui.button(a.id() + "##" + view.id(), a.icon(), a.label(), a.tone(), a.enabled())) {
                a.run().run();
                intent = a.intent();
            }
            bx += ui.buttonWidth(a.icon(), a.label(), a.tone()) + m.u(2);
        }
        return intent;
    }

    /** Left-aligned footer text, or {@code null} when the state has none. */
    private static String footerNote(ClientStatus status) {
        return switch (status) {
            case ClientStatus.Loading ignored -> "Loading game state…";
            case ClientStatus.Running ignored -> null;
            case ClientStatus.Idle ignored -> null;
            case ClientStatus.Lost ignored -> null;
            case ClientStatus.Reconnecting ignored -> null;
            case ClientStatus.Crashed ignored -> null;
        };
    }

    private static List<Action> footerActions(ClientView view, ClientActions actions) {
        String id = view.id();
        Runnable none = () -> { };
        List<Action> list = new ArrayList<>();
        switch (view.status()) {
            case ClientStatus.Running ignored -> {
                list.add(new Action("cfg", Icons.SLIDERS, "Configure", Tone.GHOST, true, none, Intent.CONFIGURE));
                list.add(new Action("stop", Icons.STOP, "Stop", Tone.STOP, true,
                        () -> actions.stopScript(id), Intent.NONE));
            }
            case ClientStatus.Idle ignored ->
                    list.add(new Action("start", Icons.PLAY, "Start script", Tone.SOFT, true, none,
                            Intent.START_SCRIPT));
            case ClientStatus.Loading ignored ->
                    list.add(new Action("start", null, "Start script", Tone.GHOST, false, none, Intent.NONE));
            case ClientStatus.Lost ignored ->
                    list.add(new Action("reconnect", Icons.REDO, "Reconnect", Tone.GHOST, true,
                            () -> actions.reconnect(id), Intent.NONE));
            case ClientStatus.Reconnecting ignored ->
                    list.add(new Action("cancel", null, "Cancel", Tone.GHOST, true,
                            () -> actions.cancelReconnect(id), Intent.NONE));
            case ClientStatus.Crashed ignored -> {
                list.add(new Action("log", null, "View log", Tone.GHOST, true,
                        () -> actions.viewLog(id), Intent.NONE));
                list.add(new Action("restart", Icons.REDO, "Restart", Tone.SOFT, true,
                        () -> actions.restartScript(id), Intent.NONE));
            }
        }
        return list;
    }
}
