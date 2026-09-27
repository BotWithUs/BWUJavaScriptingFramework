package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;

/**
 * The Start on… dialog: every connected client with a tick box (the ones
 * already running the script, or that cannot take it, disabled with the
 * reason), then the clients that are not connected (a remembered account's
 * can be ticked, and starts when it is back), and "Start on N".
 */
final class StartOnDialog {

    private static final String POPUP_ID = "##installed-start-on";
    private static final float WIDTH_EM = 34.667f;
    private static final float MAX_HEIGHT_EM = 37.333f;
    private static final float HEAD_EM = 3.733f;
    private static final float GROUP_EM = 1.867f;
    private static final float PICK_EM = 2.4f;
    private static final float BOX_EM = 1.067f;
    private static final float BOX_RADIUS_PX = 3f;
    private static final float TICK_STROKE_PX = 1.8f;
    private static final float TICK_START_X = 0.24f;
    private static final float TICK_START_Y = 0.5f;
    private static final float TICK_MID_X = 0.42f;
    private static final float TICK_MID_Y = 0.68f;
    private static final float TICK_END_X = 0.76f;
    private static final float TICK_END_Y = 0.32f;
    private static final float DISABLED_ALPHA = 0.5f;
    private static final int STYLE_VARS = 3;
    private static final int STYLE_COLORS = 3;
    private static final String NOTE = "Scripts already running there keep running.";

    private final InstalledWidgets w;
    private boolean isOpen;

    StartOnDialog(InstalledWidgets widgets) {
        this.w = widgets;
    }

    /** Opens the popup when the state asks, and draws it while it is up. */
    void render(InstalledView view, InstalledState state) {
        if (state.takeStartOnOpening()) {
            ImGui.openPopup(POPUP_ID);
        }
        InstalledScript script = state.startOnKey().flatMap(view::find).orElse(null);
        List<StartTarget> targets = script == null ? List.of() : StartTargets.of(script, view.clients());
        place(targets);
        pushStyle();
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings;
        isOpen = ImGui.beginPopupModal(POPUP_ID, flags);
        popStyle();
        if (!isOpen) {
            return;
        }
        if (script == null || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            close(state);
            ImGui.endPopup();
            return;
        }
        content(script, targets, state);
        ImGui.endPopup();
    }

    private void close(InstalledState state) {
        state.closeStartOn();
        ImGui.closeCurrentPopup();
        isOpen = false;
    }

    private float bodyHeight(List<StartTarget> targets) {
        long connected = targets.stream().filter(StartTarget::isConnected).count();
        int groups = connected == targets.size() ? 1 : 2;
        return w.fs() * GROUP_EM * groups + w.fs() * PICK_EM * Math.max(1, targets.size()) + w.m().u(2);
    }

    private float footHeight() {
        return w.m().controlHeight() + w.m().u(3) * 2f;
    }

    private void place(List<StartTarget> targets) {
        var vp = ImGui.getMainViewport();
        float margin = w.m().u(5) * 2f;
        float width = Math.min(w.fs() * WIDTH_EM, vp.getSizeX() - margin);
        float wanted = w.fs() * HEAD_EM + bodyHeight(targets) + footHeight();
        float height = Math.min(Math.min(wanted, w.fs() * MAX_HEIGHT_EM), vp.getSizeY() - margin);
        ImGui.setNextWindowSize(width, height);
        ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - width) * 0.5f,
                vp.getPosY() + (vp.getSizeY() - height) * 0.5f);
    }

    private void pushStyle() {
        ImGuiTheme.Metrics m = w.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m.radiusXl());
        ImGui.pushStyleVar(ImGuiStyleVar.PopupBorderSize, m.hairline());
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ModalWindowDimBg, ImGuiTheme.COL_SCRIM);
    }

    private static void popStyle() {
        ImGui.popStyleColor(STYLE_COLORS);
        ImGui.popStyleVar(STYLE_VARS);
    }

    private void content(InstalledScript script, List<StartTarget> targets, InstalledState state) {
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float width = ImGui.getWindowWidth();
        float height = ImGui.getWindowHeight();
        if (header(script, state, x, y, width)) {
            return;
        }
        float top = y + w.fs() * HEAD_EM;
        float bodyH = height - w.fs() * HEAD_EM - footHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, top, x + width, top, ImGuiTheme.COL_BORDER, w.m().hairline());
        ImGui.setCursorScreenPos(x, top + w.m().hairline());
        ImGui.beginChild("##start-on-body", width, bodyH - w.m().hairline(), ImGuiChildFlags.None, 0);
        body(targets, state, width);
        ImGui.endChild();
        footer(script, targets, state, x, top + bodyH, width);
    }

    /** The title and the close button; returns true when it closed the dialog. */
    private boolean header(InstalledScript script, InstalledState state, float x, float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = w.fs() * HEAD_EM;
        String title = "Start " + script.name();
        ui.textCentredY(draw, ui.fonts().bodyMedium(), x + m.u(4), y, h, ImGuiTheme.COL_FG, title);
        float onX = x + m.u(4) + ui.width(ui.fonts().bodyMedium(), title) + m.u(1);
        ui.textCentredY(draw, ui.fonts().body(), onX, y, h, ImGuiTheme.COL_FG2, "on…");
        ImGui.setCursorScreenPos(x + width - m.u(3) - m.controlSmallHeight(), y + (h - m.controlSmallHeight()) * 0.5f);
        if (w.iconButton("##start-on-close", Icons.XMARK, ImGuiTheme.COL_FG2, ImGuiTheme.COL_SURFACE, "Close (Esc)")) {
            close(state);
            return true;
        }
        return false;
    }

    private void body(List<StartTarget> targets, InstalledState state, float width) {
        float y = ImGui.getCursorScreenPosY();
        List<StartTarget> connected = targets.stream().filter(StartTarget::isConnected).toList();
        List<StartTarget> offline = targets.stream().filter(t -> !t.isConnected()).toList();
        y = group("Connected clients", connected, state, y, width, "No clients are connected.");
        if (!offline.isEmpty()) {
            y = group("Not connected", offline, state, y, width, "");
        }
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), y);
        ImGui.dummy(width, w.m().u(2));
    }

    private float group(String title, List<StartTarget> targets, InstalledState state, float y, float width,
                        String whenEmpty) {
        float x = ImGui.getWindowPosX();
        float gh = w.fs() * GROUP_EM;
        w.heading(ImGui.getWindowDrawList(), x + w.m().u(4), y + gh - w.ui().fonts().captionMedium().getFontSize()
                - w.m().u(1), title);
        float cy = y + gh;
        if (targets.isEmpty()) {
            w.ui().textCentredY(ImGui.getWindowDrawList(), w.ui().fonts().small(), x + w.m().u(4), cy,
                    w.fs() * PICK_EM, ImGuiTheme.COL_FG2, whenEmpty);
            return cy + w.fs() * PICK_EM;
        }
        for (StartTarget t : targets) {
            pick(t, state, x, cy, width);
            cy += w.fs() * PICK_EM;
        }
        return cy;
    }

    /** One client: tick box, name and why it can or cannot be ticked. */
    private void pick(StartTarget t, InstalledState state, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float h = w.fs() * PICK_EM;
        ImGui.setCursorScreenPos(x, y);
        ImGui.beginDisabled(!t.isSelectable());
        boolean clicked = ImGui.invisibleButton("##pick:" + t.clientId(), width, h);
        ImGui.endDisabled();
        boolean isHovered = ImGui.isMouseHoveringRect(x, y, x + width, y + h) && ImGui.isWindowHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isHovered && t.isSelectable()) {
            draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE);
        }
        float alpha = t.isSelectable() ? 1f : DISABLED_ALPHA;
        boolean isTicked = t.isSelectable() && state.isTicked(t.clientId());
        tickBox(draw, x + m.u(4), y + (h - w.fs() * BOX_EM) * 0.5f, isTicked, alpha);
        float nameX = x + m.u(4) + w.fs() * BOX_EM + m.u(3);
        ui.textCentredY(draw, ui.fonts().small(), nameX, y, h, Controls.scaleAlpha(ImGuiTheme.COL_FG, alpha),
                t.name());
        float noteW = ui.width(ui.fonts().caption(), t.note());
        ui.textCentredY(draw, ui.fonts().caption(), x + width - m.u(4) - noteW, y, h,
                Controls.scaleAlpha(ImGuiTheme.COL_FG2, alpha), t.note());
        if (isHovered) {
            t.eligibility().tooltip().ifPresent(ImGui::setTooltip);
        }
        if (clicked && t.isSelectable()) {
            state.toggleTick(t.clientId());
        }
    }

    private void tickBox(ImDrawList draw, float x, float y, boolean isTicked, float alpha) {
        float size = w.fs() * BOX_EM;
        if (isTicked) {
            draw.addRectFilled(x, y, x + size, y + size, ImGuiTheme.COL_ACCENT, BOX_RADIUS_PX);
            draw.pathClear();
            draw.pathLineTo(x + size * TICK_START_X, y + size * TICK_START_Y);
            draw.pathLineTo(x + size * TICK_MID_X, y + size * TICK_MID_Y);
            draw.pathLineTo(x + size * TICK_END_X, y + size * TICK_END_Y);
            draw.pathStroke(ImGuiTheme.COL_ON_ACCENT, 0, TICK_STROKE_PX);
            return;
        }
        draw.addRectFilled(x, y, x + size, y + size, Controls.scaleAlpha(ImGuiTheme.COL_BG, alpha), BOX_RADIUS_PX);
        draw.addRect(x + 0.5f, y + 0.5f, x + size - 0.5f, y + size - 0.5f,
                Controls.scaleAlpha(ImGuiTheme.COL_BORDER_HOVER, alpha), BOX_RADIUS_PX);
    }

    /** Says when ticked clients that are not connected will start; otherwise what a start leaves alone. */
    private static String note(List<StartTarget> targets, List<String> ticked) {
        long waiting = targets.stream().filter(t -> t.startsWhenBack() && ticked.contains(t.clientId())).count();
        if (waiting == 0) {
            return NOTE;
        }
        return waiting == 1
                ? "1 not connected: it starts when it is back."
                : waiting + " not connected: they start when they are back.";
    }

    private void footer(InstalledScript script, List<StartTarget> targets, InstalledState state, float x,
                        float y, float width) {
        Controls ui = w.ui();
        ImGuiTheme.Metrics m = w.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
        float h = footHeight();
        List<String> ticked = state.tickedAmong(targets);
        String go = "Start on " + ticked.size();
        float goW = ui.buttonWidth(Icons.PLAY, go, Tone.PRIMARY);
        float cancelW = ui.buttonWidth(null, "Cancel", Tone.GHOST);
        float by = y + (h - m.controlHeight()) * 0.5f;
        float noteW = width - m.u(4) * 2f - goW - cancelW - m.u(2) * 2f;
        ui.textCentredY(draw, ui.fonts().caption(), x + m.u(4), y, h, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().caption(), note(targets, ticked), noteW));
        ImGui.setCursorScreenPos(x + width - m.u(4) - goW - m.u(2) - cancelW, by);
        if (ui.button("##start-on-cancel", null, "Cancel", Tone.GHOST, true)) {
            close(state);
            return;
        }
        ImGui.setCursorScreenPos(x + width - m.u(4) - goW, by);
        if (ui.button("##start-on-go", Icons.PLAY, go, Tone.PRIMARY, !ticked.isEmpty())) {
            state.confirmStartOn(script.key(), ticked);
            ImGui.closeCurrentPopup();
            isOpen = false;
        }
    }
}
