package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.gui.Controls.Segment;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;

/**
 * The top bar both modes share: the one logo mark, the product name, and the
 * Normal / Advanced switch with its F12 hint. Session status lives in the status
 * bar now, not here.
 */
public class TopBar {

    private static final List<Segment> MODES = List.of(Segment.of("Normal"), Segment.of("Advanced"));
    private static final AppMode[] MODE_ORDER = {AppMode.NORMAL, AppMode.ADVANCED};

    private static final float LOGO_EM = 1.467f;
    private static final float LOGO_EYE_EM = 0.267f;
    private static final float LOGO_EYE_INSET_EM = 0.333f;
    private static final float LOGO_EYE_TOP_EM = 0.533f;
    private static final float LOGO_BAR_EM = 0.133f;
    private static final float LOGO_BAR_BOTTOM_EM = 0.267f;

    private final Controls ui;

    public TopBar(Controls ui) {
        this.ui = ui;
    }

    public float height() {
        return ui.m().topBarHeight();
    }

    /** Draws the bar across the current window; returns the mode clicked, or {@code null}. */
    public AppMode render(AppMode current) {
        ImGuiTheme.Metrics m = ui.m();
        float h = height();
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##topbar", 0f, h, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        ImGui.popStyleColor();

        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float w = ImGui.getWindowWidth();
        draw.addLine(x, y + h - 0.5f, x + w, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.hairline());

        float logo = ui.fonts().body().getFontSize() * LOGO_EM;
        float left = x + m.u(4);
        drawLogo(draw, left, y + (h - logo) * 0.5f, logo);
        ImFont brand = ui.fonts().bodyMedium();
        ui.textCentredY(draw, brand, left + logo + m.u(3), y, h, ImGuiTheme.COL_FG, "BotWithUs");

        AppMode clicked = renderModeSwitch(current, x + w - m.u(4), y, h);
        ImGui.endChild();
        return clicked;
    }

    private AppMode renderModeSwitch(AppMode current, float right, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        float segW = ui.segmentedWidth(MODES);
        float segH = m.controlSmallHeight();
        float segX = right - segW;
        int selected = current == AppMode.ADVANCED ? 1 : 0;
        ImGui.setCursorScreenPos(segX, y + (h - segH) * 0.5f);
        int clicked = ui.segmented("##mode", MODES, selected, 0f, segH);
        float kbdW = ui.kbdWidth("F12");
        ui.kbd(ImGui.getWindowDrawList(), segX - m.u(3) - kbdW, y + (h - ui.kbdHeight()) * 0.5f, "F12");
        return clicked >= 0 ? MODE_ORDER[clicked] : null;
    }

    /** The logo: a visor — one rounded tile, two accent eyes and a bar for a mouth. */
    private void drawLogo(ImDrawList draw, float x, float y, float size) {
        float fs = ui.fonts().body().getFontSize();
        float r = ui.m().radius();
        draw.addRectFilled(x, y, x + size, y + size, ImGuiTheme.COL_ELEVATED, r);
        draw.addRect(x + 0.5f, y + 0.5f, x + size - 0.5f, y + size - 0.5f, ImGuiTheme.COL_BORDER, r);
        float eye = fs * LOGO_EYE_EM;
        float inset = fs * LOGO_EYE_INSET_EM;
        float eyeY = y + fs * LOGO_EYE_TOP_EM + eye * 0.5f;
        draw.addCircleFilled(x + inset + eye * 0.5f, eyeY, eye * 0.5f, ImGuiTheme.COL_ACCENT);
        draw.addCircleFilled(x + size - inset - eye * 0.5f, eyeY, eye * 0.5f, ImGuiTheme.COL_ACCENT);
        float barH = Math.max(ui.m().hairline(), fs * LOGO_BAR_EM);
        float barY = y + size - fs * LOGO_BAR_BOTTOM_EM - barH;
        draw.addRectFilled(x + inset, barY, x + size - inset, barY + barH, ImGuiTheme.COL_BORDER_HOVER, barH * 0.5f);
    }
}
