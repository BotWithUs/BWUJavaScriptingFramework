package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.installed.RunSummary.Part;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * One row of the list: category icon, name and version (with the Store's
 * newer version when there is one), the source badge, a dot per client with
 * the running count, and Start on… plus Stop everywhere. On a narrow list the
 * source column folds into a glyph after the version.
 */
final class ScriptRowPainter {

    /** The prototype's row height, 56 px at a 15 px body font. */
    private static final float ROW_EM = 3.733f;
    private static final float HEAD_EM = 2.133f;
    private static final float SOURCE_COL_EM = 6.933f;
    private static final float RUNS_MIN_EM = 10f;
    /**
     * The list width below which the source column folds into the name line and
     * Start on… becomes an icon, so the name keeps its room.
     */
    private static final float WIDE_LIST_EM = 50f;
    private static final float NAME_SHARE = 1.5f;
    private static final float RUNS_SHARE = 1f;
    private static final int MAX_DOTS = 12;
    private static final String START_ON = "Start on…";

    /** Where each column starts and how wide it is, for one list width. */
    record Columns(float icon, float name, float nameW, float source, boolean isWide, float runs, float runsW,
                   float actions, float actionsW) {}

    private final InstalledWidgets w;

    ScriptRowPainter(InstalledWidgets widgets) {
        this.w = widgets;
    }

    float rowHeight() {
        return w.fs() * ROW_EM;
    }

    float headHeight() {
        return w.fs() * HEAD_EM;
    }

    Columns columns(float x, float width) {
        ImGuiTheme.Metrics m = w.m();
        boolean isWide = width >= w.fs() * WIDE_LIST_EM;
        float actionsW = actionsWidth(isWide);
        float left = x + m.u(4);
        float right = x + width - m.u(3);
        float sourceW = isWide ? w.fs() * SOURCE_COL_EM + m.u(3) : 0f;
        float flex = right - left - m.iconTile() - actionsW - m.u(3) * 3f - sourceW;
        float runsW = Math.max(w.fs() * RUNS_MIN_EM, flex * RUNS_SHARE / (NAME_SHARE + RUNS_SHARE));
        float nameW = Math.max(0f, flex - runsW);
        float name = left + m.iconTile() + m.u(3);
        float source = name + nameW + m.u(3);
        float runs = source + sourceW;
        return new Columns(left, name, nameW, source, isWide, runs, runsW, right - actionsW, actionsW);
    }

    private float actionsWidth(boolean isWide) {
        float start = isWide ? w.ui().buttonWidth(Icons.PLAY, START_ON, Tone.GHOST) : w.m().controlSmallHeight();
        return start + w.m().u(0.5f) + w.m().controlSmallHeight();
    }

    /** The column captions over the rows. */
    void head(ImDrawList draw, Columns c, float y) {
        Controls ui = w.ui();
        ImFont font = ui.fonts().captionMedium();
        float h = headHeight();
        ui.textCentredY(draw, font, c.name(), y, h, ImGuiTheme.COL_FG2, "Script");
        if (c.isWide()) {
            ui.textCentredY(draw, font, c.source(), y, h, ImGuiTheme.COL_FG2, "Source");
        }
        ui.textCentredY(draw, font, c.runs(), y, h, ImGuiTheme.COL_FG2, "On clients");
    }

    /**
     * Paints a row's passive parts, its top at {@code y}. The buttons are drawn
     * by {@link #actions}; while Stop everywhere waits for its confirm, the
     * runs column gives up the room its wider buttons need.
     */
    void paint(ImDrawList draw, InstalledScript s, InstalledState state, Columns c, float y) {
        ImGuiTheme.Metrics m = w.m();
        float h = rowHeight();
        float tile = m.iconTile();
        IconTone tone = IconTone.of(s);
        w.ui().iconTile(draw, c.icon(), y + (h - tile) * 0.5f, tile, CategoryStyle.icon(s.identity().category()),
                tone.fg(), tone.bg());
        paintName(draw, s, c, y, h);
        if (c.isWide()) {
            w.sourceBadge(draw, c.source(), y + (h - w.badgeHeight()) * 0.5f, s.provenance().source());
        }
        float runsRight = state.isStopArmed(s.key())
                ? Math.min(c.runs() + c.runsW(), confirmLeft(s, c) - w.m().u(3)) : c.runs() + c.runsW();
        paintRuns(draw, s, c, y, h, runsRight);
    }

    private void paintName(ImDrawList draw, InstalledScript s, Columns c, float y, float h) {
        Controls ui = w.ui();
        float line1 = ui.fonts().smallMedium().getFontSize();
        float line2 = ui.fonts().caption().getFontSize();
        float top = y + (h - line1 - line2 - w.m().u(1)) * 0.5f;
        draw.pushClipRect(c.name(), y, c.name() + c.nameW(), y + h, true);
        float x = c.name();
        ui.text(draw, ui.fonts().smallMedium(), x, top, ImGuiTheme.COL_FG, s.name());
        x += ui.width(ui.fonts().smallMedium(), s.name()) + w.m().u(1.25f);
        x = paintNameSuffix(draw, s, c, x, top + line1 * 0.5f);
        String sub = subline(s);
        ui.text(draw, ui.fonts().caption(), c.name(), top + line1 + w.m().u(1),
                s.provenance().isStoreNotLoaded() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), sub, c.nameW()));
        draw.popClipRect();
    }

    /** The version, the folded source glyph on a narrow list, and the update pill; returns the next x. */
    private float paintNameSuffix(ImDrawList draw, InstalledScript s, Columns c, float x, float cy) {
        Controls ui = w.ui();
        ImFont mono = ui.fonts().monoCaption();
        float cx = x;
        String version = versionLabel(s.identity().version());
        if (!version.isEmpty()) {
            ui.text(draw, mono, cx, cy - mono.getFontSize() * 0.5f, ImGuiTheme.COL_FG2, version);
            cx += ui.width(mono, version) + w.m().u(1.5f);
        }
        if (!c.isWide()) {
            ImFont cap = ui.fonts().caption();
            String glyph = s.provenance().source().icon();
            ui.text(draw, cap, cx, cy - cap.getFontSize() * 0.5f, ImGuiTheme.COL_FG3, glyph);
            w.tooltipOver(cx, cy - cap.getFontSize() * 0.5f, ui.width(cap, glyph), cap.getFontSize(),
                    s.provenance().source().label() + ": " + s.provenance().source().hint());
            cx += ui.width(cap, glyph) + w.m().u(1.5f);
        }
        if (s.provenance().update().isPresent()) {
            String badge = s.provenance().update().get().badge();
            w.pill(draw, cx, cy, badge, ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT);
            cx += w.pillWidth(badge);
        }
        return cx;
    }

    private static String subline(InstalledScript s) {
        if (s.provenance().isStoreNotLoaded()) {
            return "Store · not loaded since the host restarted";
        }
        return s.identity().description();
    }

    /** {@code "2.0"} as {@code "v2.0"}; a version that already says v, or none, as it is. */
    static String versionLabel(String version) {
        if (version.isBlank()) {
            return "";
        }
        return version.startsWith("v") || version.startsWith("V") ? version : "v" + version;
    }

    private void paintRuns(ImDrawList draw, InstalledScript s, Columns c, float y, float h, float right) {
        Controls ui = w.ui();
        ImFont font = ui.fonts().caption();
        float ty = y + (h - font.getFontSize()) * 0.5f;
        draw.pushClipRect(c.runs(), y, right, y + h, true);
        float x = c.runs();
        int shown = Math.min(MAX_DOTS, s.runs().size());
        for (int i = 0; i < shown; i++) {
            w.dot(draw, x, y + (h - w.dotSize()) * 0.5f, s.runs().get(i).state());
            x += w.dotStride();
        }
        if (shown > 0) {
            x += w.m().u(1.25f);
        }
        for (Part p : s.summary().parts()) {
            ImFont f = p.tone() == RunSummary.Tone.STRONG ? ui.fonts().captionMedium() : font;
            ui.text(draw, f, x, ty, partColor(p.tone()), p.text());
            x += ui.width(f, p.text());
        }
        draw.popClipRect();
        w.tooltipOver(c.runs(), y, c.runsW(), h, dotsTooltip(s));
    }

    private static String dotsTooltip(InstalledScript s) {
        StringBuilder out = new StringBuilder();
        for (ClientRun r : s.runs()) {
            out.append(out.isEmpty() ? "" : "\n").append(r.clientName()).append(": ").append(r.detail());
        }
        return out.isEmpty() ? RunSummary.NOWHERE : out.toString();
    }

    private static int partColor(RunSummary.Tone tone) {
        return switch (tone) {
            case STRONG -> ImGuiTheme.COL_FG;
            case PLAIN -> ImGuiTheme.COL_FG2;
            case WARN -> ImGuiTheme.COL_WARN;
            case DANGER -> ImGuiTheme.COL_DANGER;
        };
    }

    /**
     * The row's buttons, right-aligned in the actions column: Start on… and a
     * red stop, or, once stop is armed, the confirm and a cancel. A Store script
     * that is not loaded offers Install again instead.
     */
    void actions(InstalledScript s, InstalledState state, Columns c, float y) {
        ImGuiTheme.Metrics m = w.m();
        float bh = m.controlSmallHeight();
        float by = y + (rowHeight() - bh) * 0.5f;
        String id = "##row:" + s.key();
        if (s.provenance().isStoreNotLoaded()) {
            installAgain(s, id, state, c, by, bh);
            return;
        }
        if (state.isStopArmed(s.key())) {
            confirmStop(s, state, c, by, id);
            return;
        }
        ImGui.setCursorScreenPos(c.actions(), by);
        if (startOnButton(id, c, bh)) {
            state.openStartOn(s.key());
        }
        if (s.isActive()) {
            ImGui.setCursorScreenPos(c.actions() + c.actionsW() - bh, by);
            if (w.iconButton(id + ":stop", Icons.STOP, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                    "Stop " + s.name() + " on every client")) {
                state.armStop(s.key());
            }
        }
    }

    /** The labelled Start on… on a wide list, a play icon on a narrow one. */
    private boolean startOnButton(String id, Columns c, float bh) {
        if (c.isWide()) {
            return w.ui().button(id + ":start", Icons.PLAY, START_ON, Tone.GHOST, true, bh);
        }
        return w.iconButton(id + ":start", Icons.PLAY, ImGuiTheme.COL_FG, ImGuiTheme.COL_ELEVATED, START_ON);
    }

    /** Where the armed stop's confirm button starts: right-aligned, before its cancel. */
    private float confirmLeft(InstalledScript s, Columns c) {
        float bh = w.m().controlSmallHeight();
        float bw = w.ui().buttonWidth(Icons.STOP, confirmLabel(s), Tone.STOP);
        return c.actions() + c.actionsW() - bh - w.m().u(0.5f) - bw;
    }

    private static String confirmLabel(InstalledScript s) {
        return "Stop on " + s.activeCount();
    }

    private void installAgain(InstalledScript s, String id, InstalledState state, Columns c, float by, float bh) {
        float bw = w.ui().buttonWidth(Icons.BAG_SHOPPING, "Install again", Tone.SOFT);
        ImGui.setCursorScreenPos(c.actions() + c.actionsW() - bw, by);
        if (w.ui().button(id + ":install", Icons.BAG_SHOPPING, "Install again", Tone.SOFT, true, bh)) {
            state.showInStore(s);
        }
    }

    private void confirmStop(InstalledScript s, InstalledState state, Columns c, float by, String id) {
        float bh = w.m().controlSmallHeight();
        String label = confirmLabel(s);
        ImGui.setCursorScreenPos(confirmLeft(s, c), by);
        if (w.ui().button(id + ":confirm", Icons.STOP, label, Tone.STOP, true, bh)) {
            state.confirmStop(s.key());
        }
        ImGui.setCursorScreenPos(c.actions() + c.actionsW() - bh, by);
        if (w.iconButton(id + ":cancel", Icons.XMARK, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED, "Keep running")) {
            state.disarmStop();
        }
    }
}
