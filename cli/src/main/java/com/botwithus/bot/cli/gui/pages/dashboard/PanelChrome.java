package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

/**
 * The Dashboard's shared drawing: the bordered surface panels, their header
 * row and footer, and the small controls the design uses inside them (icon
 * buttons, pills, status dots) that the shared widget kit does not have.
 */
final class PanelChrome {

    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final int PANEL_STYLE_VARS = 3;
    private static final int PANEL_STYLE_COLORS = 2;
    private static final float PILL_PAD_EM = 0.533f;
    private static final float PILL_HEIGHT_EM = 1.467f;

    private final Controls ui;

    PanelChrome(Controls ui) {
        this.ui = ui;
    }

    Controls ui() {
        return ui;
    }

    ImGuiTheme.Metrics m() {
        return ui.m();
    }

    // ── Panels ──────────────────────────────────────────────────────────

    /**
     * Opens a bordered, rounded surface panel {@code width} wide whose height
     * follows its content. Everything drawn until {@link #end()} lands inside it,
     * with no padding: the panel's rows pad themselves.
     */
    void begin(String id, float width) {
        ImGuiTheme.Metrics m = m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, m.radiusLarge());
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, m.hairline());
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        ImGui.beginChild(id, width, 0f, ImGuiChildFlags.Border | ImGuiChildFlags.AutoResizeY,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleColor(PANEL_STYLE_COLORS);
        ImGui.popStyleVar(PANEL_STYLE_VARS);
    }

    void end() {
        ImGui.endChild();
    }

    /** The header row's height: a small control plus the design's padding above and below. */
    float headerHeight() {
        return m().controlSmallHeight() + m().u(3) * 2f;
    }

    /**
     * Draws a panel's header row at the top of the current panel: the title, a
     * mono count after it, and the rule beneath. Leaves the cursor below the rule.
     *
     * @return the screen y at which a control of small height sits centred in the row
     */
    float header(String title, String count) {
        ImGuiTheme.Metrics m = m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX() + m.u(4);
        float y = ImGui.getWindowPosY();
        float h = headerHeight();
        ui.textCentredY(draw, ui.fonts().smallMedium(), x, y, h, ImGuiTheme.COL_FG, title);
        if (!count.isEmpty()) {
            float cx = x + ui.width(ui.fonts().smallMedium(), title) + m.u(2);
            ui.textCentredY(draw, ui.fonts().monoCaption(), cx, y, h, ImGuiTheme.COL_FG2, count);
        }
        rule(y + h);
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), y + h);
        return y + (h - m.controlSmallHeight()) * 0.5f;
    }

    /** The screen x a control {@code width} wide starts at to end at the header's right padding. */
    float headerRight(float width) {
        return ImGui.getWindowPosX() + ImGui.getWindowWidth() - m().u(3) - width;
    }

    /** A hairline across the current panel at screen {@code y}. */
    void rule(float y) {
        float x0 = ImGui.getWindowPosX();
        ImGui.getWindowDrawList().addLine(x0, y, x0 + ImGui.getWindowWidth(), y, ImGuiTheme.COL_BORDER,
                m().hairline());
    }

    /** A caption line at the bottom of the panel, over a rule. */
    void footer(String text) {
        ImGuiTheme.Metrics m = m();
        float y = ImGui.getCursorScreenPosY();
        rule(y);
        ImFont font = ui.fonts().caption();
        float h = font.getFontSize() + m.u(2) * 2f;
        float maxW = ImGui.getWindowWidth() - m.u(4) * 2f;
        float lineH = font.getFontSize() * DashboardPage.LINE_HEIGHT;
        float cy = y + m.u(2);
        for (String line : ui.wrap(font, text, maxW)) {
            ui.text(ImGui.getWindowDrawList(), font, ImGui.getWindowPosX() + m.u(4), cy, ImGuiTheme.COL_FG2, line);
            cy += lineH;
        }
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), y);
        ImGui.dummy(ImGui.getWindowWidth(), Math.max(h, cy - y + m.u(2)));
    }

    // ── Small controls ──────────────────────────────────────────────────

    /** The square icon button's side. */
    float iconButtonSize() {
        return m().controlSmallHeight();
    }

    /**
     * A square icon button at the cursor, like the design's {@code .btn.icon}:
     * transparent until hovered, then a soft wash of {@code hoverBg}.
     */
    boolean iconButton(String id, String icon, int fg, int hoverBg, boolean enabled, String tooltip) {
        float s = iconButtonSize();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, s, s);
        boolean hovered = enabled && ImGui.isItemHovered();
        ImGui.endDisabled();
        if (hovered && !tooltip.isEmpty()) {
            ImGui.setTooltip(tooltip);
        }
        float t = ui.motion().step("dash-ib:" + id, hovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + s, y + s, Controls.scaleAlpha(hoverBg, t), m().radius());
        float alpha = enabled ? 1f : ImGuiTheme.DISABLED_ALPHA;
        ImFont font = ui.fonts().caption();
        float iw = ui.width(font, icon);
        ui.textCentredY(draw, font, x + (s - iw) * 0.5f, y, s, Controls.scaleAlpha(fg, alpha), icon);
        return clicked && enabled;
    }

    /**
     * A borderless button in the secondary colour, icon then label, that goes to
     * the primary colour on hover: the design's quiet toggles like "Stack trace".
     */
    boolean textButton(String id, String icon, String label, float h) {
        ImFont iconFont = ui.fonts().caption();
        ImFont font = ui.fonts().captionMedium();
        float gap = m().u(1.5f);
        float w = ui.width(iconFont, icon) + gap + ui.width(font, label) + m().u(2) * 2f;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        float t = ui.motion().step("dash-tb:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        int fg = Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        float cx = x + m().u(2);
        ui.textCentredY(draw, iconFont, cx, y, h, fg, icon);
        ui.textCentredY(draw, font, cx + ui.width(iconFont, icon) + gap, y, h, fg, label);
        return clicked;
    }

    float pillWidth(String label) {
        return ui.width(ui.fonts().monoCaption(), label) + ui.fonts().body().getFontSize() * PILL_PAD_EM * 2f;
    }

    float pillHeight() {
        return ui.fonts().body().getFontSize() * PILL_HEIGHT_EM;
    }

    /** A rounded, outlined mono chip that reads as pressed when {@code isOn}, like the log level chips. */
    boolean pill(String id, String label, boolean isOn) {
        float w = pillWidth(label);
        float h = pillHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        boolean hovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        float r = h * 0.5f;
        if (isOn) {
            draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_ELEVATED, r);
        }
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f,
                isOn ? ImGuiTheme.COL_BORDER_HOVER : ImGuiTheme.COL_BORDER, r);
        int fg = isOn || hovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        float lw = ui.width(ui.fonts().monoCaption(), label);
        ui.textCentredY(draw, ui.fonts().monoCaption(), x + (w - lw) * 0.5f, y, h, fg, label);
        return clicked;
    }

    /** A coloured dot and a medium caption label, vertically centred in a band of height {@code h}. */
    float status(ImDrawList draw, float x, float y, float h, String label, int color) {
        float dot = m().dot();
        draw.addCircleFilled(x + dot * 0.5f, y + h * 0.5f, dot * 0.5f, color);
        float lx = x + dot + m().u(1.5f);
        ui.textCentredY(draw, ui.fonts().captionMedium(), lx, y, h, color, label);
        return lx + ui.width(ui.fonts().captionMedium(), label) - x;
    }
}
