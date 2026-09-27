package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

/**
 * The small pieces the Groups page draws in several places: checkboxes, member
 * dots, link states, coloured icon buttons, dashed frames and tooltips.
 */
final class GroupWidgets {

    private static final float ROW_HOVER_ALPHA = 0.03f;
    private static final float ROW_SELECTED_ALPHA = 0.08f;

    // Font Awesome 6 solid glyphs this page uses that the shared icon set does not name.
    static final String PEN = "\uF304";         // fa-pen
    static final String USER_MINUS = "\uF503";  // fa-user-minus
    static final String USER_PLUS = "\uF234";   // fa-user-plus
    static final String MINUS = "\uF068";       // fa-minus
    static final String RIGHT_LEFT = "\uF362";  // fa-right-left

    static final int ROW_HOVER = Controls.scaleAlpha(ImGuiTheme.COL_FG, ROW_HOVER_ALPHA);
    static final int ROW_SELECTED = Controls.scaleAlpha(ImGuiTheme.COL_INFO, ROW_SELECTED_ALPHA);

    private static final float CHECKBOX_EM = 1.067f;
    private static final float TOOLTIP_WIDTH_EM = 22f;
    private static final float SPINNER_ARC = (float) (Math.PI * 1.5);
    private static final float SPINNER_TURNS_PER_S = 1.1f;
    private static final float SPINNER_STROKE_EM = 0.133f;
    private static final float SPINNER_RADIUS_EM = 0.4f;
    private static final float DASH_EM = 0.4f;
    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final int DASHED_SIDES = 4;
    private static final int MODAL_COLORS = 3;
    private static final int MODAL_VARS = 3;

    private final Controls ui;

    GroupWidgets(Controls ui) {
        this.ui = ui;
    }

    Controls ui() {
        return ui;
    }

    ImGuiTheme.Metrics m() {
        return ui.m();
    }

    float fs() {
        return ui.fonts().body().getFontSize();
    }

    float checkboxSize() {
        return fs() * CHECKBOX_EM;
    }

    /** A token checkbox at (x, y), vertically centred in {@code h}; returns true when clicked. */
    boolean checkbox(String id, float x, float y, float h, boolean isChecked, boolean isEnabled) {
        float s = checkboxSize();
        float top = y + (h - s) * 0.5f;
        ImGui.setCursorScreenPos(x, top);
        ImGui.beginDisabled(!isEnabled);
        boolean clicked = ImGui.invisibleButton(id, s, s);
        boolean isHovered = isEnabled && ImGui.isItemHovered();
        ImGui.endDisabled();
        ImDrawList draw = ImGui.getWindowDrawList();
        float alpha = isEnabled ? 1f : ImGuiTheme.DISABLED_ALPHA;
        float r = m().radiusSmall();
        if (isChecked) {
            draw.addRectFilled(x, top, x + s, top + s, Controls.scaleAlpha(ImGuiTheme.COL_ACCENT, alpha), r);
            float iw = ui.width(ui.fonts().caption(), Icons.CHECK);
            ui.textCentredY(draw, ui.fonts().caption(), x + (s - iw) * 0.5f, top, s,
                    Controls.scaleAlpha(ImGuiTheme.COL_ON_ACCENT, alpha), Icons.CHECK);
        } else {
            draw.addRectFilled(x, top, x + s, top + s, ImGuiTheme.COL_BG, r);
            int border = isHovered ? ImGuiTheme.COL_BORDER_HOVER : ImGuiTheme.COL_FG3;
            draw.addRect(x + 0.5f, top + 0.5f, x + s - 0.5f, top + s - 0.5f, Controls.scaleAlpha(border, alpha), r);
        }
        return clicked && isEnabled;
    }

    /** A member's dot, centred on (cx, cy): filled in its state's colour, hollow when closed. */
    void dot(ImDrawList draw, float cx, float cy, MemberHealth health) {
        float r = m().dot() * 0.5f;
        if (health == MemberHealth.CLOSED) {
            draw.addCircle(cx, cy, r - 0.5f, ImGuiTheme.COL_FG3);
            return;
        }
        draw.addCircleFilled(cx, cy, r, colorOf(health));
    }

    static int colorOf(MemberHealth health) {
        return switch (health) {
            case RUNNING -> ImGuiTheme.COL_ACCENT;
            case STALLED, RECONNECTING -> ImGuiTheme.COL_WARN;
            case CRASHED -> ImGuiTheme.COL_DANGER;
            case IDLE, CLOSED -> ImGuiTheme.COL_FG3;
        };
    }

    /** The link column: a dot, spinner or icon and a word, vertically centred in {@code h}. */
    void link(ImDrawList draw, float x, float y, float h, MemberLink link) {
        ImFont font = ui.fonts().captionMedium();
        float cy = y + h * 0.5f;
        float gap = m().u(1.5f);
        switch (link) {
            case CONNECTED -> labelWithDot(draw, x, y, h, "Connected", ImGuiTheme.COL_ACCENT);
            case NOT_RESPONDING -> labelWithDot(draw, x, y, h, "Not responding", ImGuiTheme.COL_WARN);
            case RECONNECTING -> {
                float r = font.getFontSize() * SPINNER_RADIUS_EM;
                spinner(draw, x + r, cy, r, ImGuiTheme.COL_WARN);
                ui.textCentredY(draw, font, x + r * 2f + gap, y, h, ImGuiTheme.COL_WARN, "Reconnecting");
            }
            case CLOSED -> {
                ui.textCentredY(draw, ui.fonts().caption(), x, y, h, ImGuiTheme.COL_FG2, Icons.POWER);
                float iw = ui.width(ui.fonts().caption(), Icons.POWER);
                ui.textCentredY(draw, font, x + iw + gap, y, h, ImGuiTheme.COL_FG2, "Client closed");
            }
        }
    }

    private void labelWithDot(ImDrawList draw, float x, float y, float h, String label, int col) {
        float r = m().dot() * 0.5f;
        draw.addCircleFilled(x + r, y + h * 0.5f, r, col);
        ui.textCentredY(draw, ui.fonts().captionMedium(), x + r * 2f + m().u(1.5f), y, h, col, label);
    }

    /** A three-quarter ring turning slowly: work in progress. */
    void spinner(ImDrawList draw, float cx, float cy, float r, int col) {
        float start = (float) (ImGui.getTime() * Math.PI * 2.0 * SPINNER_TURNS_PER_S);
        draw.addCircle(cx, cy, r, Controls.scaleAlpha(col, ImGuiTheme.DISABLED_ALPHA * 0.5f));
        draw.pathArcTo(cx, cy, r, start, start + SPINNER_ARC);
        draw.pathStroke(col, 0, Math.max(1f, fs() * SPINNER_STROKE_EM));
    }

    /**
     * A square icon button at the cursor, the size of a small control, in the
     * given colour, filling with {@code hoverBg} on hover. Shows {@code tip}
     * while hovered.
     */
    boolean iconButton(String id, String icon, int fg, int hoverBg, String tip) {
        float s = m().controlSmallHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, s, s);
        boolean isHovered = ImGui.isItemHovered();
        float t = Motion.step("grp-icon:" + id, isHovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + s, y + s, Controls.scaleAlpha(hoverBg, t), m().radius());
        float iw = ui.width(ui.fonts().caption(), icon);
        ui.textCentredY(draw, ui.fonts().caption(), x + (s - iw) * 0.5f, y, s, fg, icon);
        if (isHovered) {
            tooltip(tip);
        }
        return clicked;
    }

    /** A rectangle drawn with dashes, for an empty slot. */
    void dashedRect(ImDrawList draw, float x0, float y0, float x1, float y1, int col) {
        float dash = fs() * DASH_EM;
        float[][] sides = {{x0, y0, x1, y0}, {x1, y0, x1, y1}, {x1, y1, x0, y1}, {x0, y1, x0, y0}};
        for (int i = 0; i < DASHED_SIDES; i++) {
            dashedLine(draw, sides[i][0], sides[i][1], sides[i][2], sides[i][3], dash, col);
        }
    }

    private static void dashedLine(ImDrawList draw, float ax, float ay, float bx, float by, float dash, int col) {
        float len = (float) Math.hypot(bx - ax, by - ay);
        float dx = (bx - ax) / len;
        float dy = (by - ay) / len;
        for (float d = 0f; d < len; d += dash * 2f) {
            float e = Math.min(len, d + dash);
            draw.addLine(ax + dx * d, ay + dy * d, ax + dx * e, ay + dy * e, col);
        }
    }

    /** A wrapped tooltip, for the item just drawn or whatever the caller found hovered. */
    void tooltip(String text) {
        if (text.isEmpty()) {
            return;
        }
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m().u(3), m().u(2));
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m().radius());
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.Text, ImGuiTheme.COL_FG);
        ImGui.beginTooltip();
        ImGui.pushFont(ui.fonts().small());
        ImGui.pushTextWrapPos(fs() * TOOLTIP_WIDTH_EM);
        ImGui.textUnformatted(text);
        ImGui.popTextWrapPos();
        ImGui.popFont();
        ImGui.endTooltip();
        ImGui.popStyleColor(3);
        ImGui.popStyleVar(2);
    }

    /**
     * Begins a centred modal in the design's modal style, at most
     * {@code widthEm} by {@code heightEm}, opening it first when {@code isOpening}.
     *
     * @return whether it is open; call {@link #endModal()} only then
     */
    boolean beginModal(String id, float widthEm, float heightEm, boolean isOpening) {
        if (isOpening) {
            ImGui.openPopup(id);
        }
        var vp = ImGui.getMainViewport();
        float margin = m().u(5) * 2f;
        float width = Math.min(fs() * widthEm, vp.getSizeX() - margin);
        float height = Math.min(fs() * heightEm, vp.getSizeY() - margin);
        ImGui.setNextWindowSize(width, height);
        ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - width) * 0.5f,
                vp.getPosY() + (vp.getSizeY() - height) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m().radiusXl());
        ImGui.pushStyleVar(ImGuiStyleVar.PopupBorderSize, m().hairline());
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ModalWindowDimBg, ImGuiTheme.COL_SCRIM);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoNavInputs | ImGuiWindowFlags.NoSavedSettings;
        boolean isOpen = ImGui.beginPopupModal(id, flags);
        ImGui.popStyleColor(MODAL_COLORS);
        ImGui.popStyleVar(MODAL_VARS);
        return isOpen;
    }

    /** Closes the modal begun last, now. */
    static void closeModal() {
        ImGui.closeCurrentPopup();
    }

    static void endModal() {
        ImGui.endPopup();
    }

    /** A modal's title row: a medium title, a grey suffix and a close button; returns true when closed. */
    boolean modalTitle(String id, String title, String suffix, float x, float y, float width) {
        float h = m().controlHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont bold = ui.fonts().bodyMedium();
        ui.textCentredY(draw, bold, x, y, h, ImGuiTheme.COL_FG, title);
        float sx = x + ui.width(bold, title);
        ui.textCentredY(draw, ui.fonts().body(), sx, y, h, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().body(), suffix, Math.max(0f, x + width - h - m().u(2) - sx)));
        ImGui.setCursorScreenPos(x + width - h, y);
        return ui.button(id, Icons.XMARK, "", Controls.Tone.ICON, true);
    }

    /** Whether the mouse is over the rectangle, and the current window is the one it would hit. */
    static boolean isHovering(float x, float y, float w, float h) {
        return ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x, y, x + w, y + h);
    }
}
