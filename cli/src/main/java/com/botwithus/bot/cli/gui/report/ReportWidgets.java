package com.botwithus.bot.cli.gui.report;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.report.ReportPreview;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The pieces of {@link ReportDialog} that are not about its stages: wrapped
 * text that takes up its own room, the framed note field, the "What will be
 * sent" disclosure and its list.
 */
final class ReportWidgets {

    private static final float LINE_EM = 1.5f;
    private static final float BULLET_INDENT_EM = 1.1f;
    private static final String BULLET = "•";
    private static final String LOADING = "Checking what will be sent…";
    /** Where a bullet sits inside its indent, as a fraction of it. */
    private static final float BULLET_X = 0.4f;
    private static final float FOCUS_RING_PX = 2f;
    private static final float BORDER_PX = 1f;
    /** A choice ring's size against the small font, its unchosen stroke, and its dot against the ring. */
    private static final float RADIO_EM = 1.1f;
    private static final float RADIO_RING = 1.5f;
    private static final float RADIO_DOT = 0.5f;
    private static final int FIELD_STYLE_VARS = 2;
    private static final int FIELD_STYLE_COLORS = 3;

    /** One line of the list: its text, its font, and whether it is a bullet. */
    record DetailLine(String text, boolean isMono, boolean isBullet) { }

    private final Controls ui;

    ReportWidgets(Controls ui) {
        this.ui = ui;
    }

    /** Draws {@code text} wrapped to {@code width} at the cursor, and moves the cursor under it. */
    void paragraph(ImFont font, int col, String text, float width) {
        paragraphAt(font, col, text, ImGui.getCursorScreenPosX(), width);
    }

    private void paragraphAt(ImFont font, int col, String text, float x, float width) {
        float y = ImGui.getCursorScreenPosY();
        float line = font.getFontSize() * LINE_EM;
        List<String> lines = ui.wrap(font, text, width);
        ImDrawList draw = ImGui.getWindowDrawList();
        for (int i = 0; i < lines.size(); i++) {
            ui.text(draw, font, x, y + i * line + (line - font.getFontSize()) * 0.5f, col, lines.get(i));
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, lines.size() * line);
    }

    /**
     * A framed multi-line field for the note, focused on its first frame.
     *
     * @return whether Ctrl+Enter was pressed in it, which sends
     */
    boolean noteField(String id, ImString text, float width, float height, boolean isFirstFrame) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGuiTheme.COL_SURFACE, m.radius());
        ImGui.pushFont(ui.fonts().small());
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, m.u(3), m.u(2));
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f);
        Controls.pushColor(ImGuiCol.FrameBg, 0);
        Controls.pushColor(ImGuiCol.FrameBgHovered, 0);
        Controls.pushColor(ImGuiCol.FrameBgActive, 0);
        if (isFirstFrame) {
            ImGui.setKeyboardFocusHere();
        }
        ImGui.inputTextMultiline(id, text, width, height);
        boolean isActive = ImGui.isItemActive();
        ImGui.popStyleColor(FIELD_STYLE_COLORS);
        ImGui.popStyleVar(FIELD_STYLE_VARS);
        ImGui.popFont();
        int border = isActive ? ImGuiTheme.COL_FOCUS : ImGuiTheme.COL_BORDER;
        draw.addRect(x, y, x + width, y + height, border, m.radius(), 0, isActive ? FOCUS_RING_PX : BORDER_PX);
        return isActive && ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.Enter, false);
    }

    /** One choice of a set: a ring, filled when chosen, and its words; returns whether it was clicked. */
    boolean radio(String id, String label, boolean isChosen, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = ui.m().controlHeight();
        boolean isClicked = ImGui.invisibleButton(id, width, h);
        boolean isHovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        float r = ui.fonts().small().getFontSize() * RADIO_EM * 0.5f;
        float cx = x + r + BORDER_PX;
        float cy = y + h * 0.5f;
        int ring = isChosen ? ImGuiTheme.COL_ACCENT : isHovered ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG3;
        draw.addCircle(cx, cy, r, ring, 0, isChosen ? FOCUS_RING_PX : BORDER_PX * RADIO_RING);
        if (isChosen) {
            draw.addCircleFilled(cx, cy, r * RADIO_DOT, ImGuiTheme.COL_ACCENT);
        }
        int text = isChosen || isHovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, ui.fonts().small(), x + r * 2f + ui.m().u(3), y, h, text, label);
        return isClicked;
    }

    /** A quiet line under the note saying what is still missing: an info glyph and the words. */
    void hint(String text, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + ui.m().u(1);
        ImFont font = ui.fonts().small();
        float indent = ui.fonts().body().getFontSize() * BULLET_INDENT_EM;
        ui.text(ImGui.getWindowDrawList(), ui.fonts().caption(), x, y + (font.getFontSize() * LINE_EM
                - ui.fonts().caption().getFontSize()) * 0.5f, ImGuiTheme.COL_INFO, Icons.INFO);
        ImGui.setCursorScreenPos(x + indent, y);
        paragraph(font, ImGuiTheme.COL_FG2, text, width - indent);
        ImGui.setCursorScreenPos(x, ImGui.getCursorScreenPosY());
    }

    /** A row with a chevron and {@code label}; returns whether it was clicked. */
    boolean disclosure(String id, String label, boolean isOpen, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = ui.m().controlHeight();
        boolean isClicked = ImGui.invisibleButton(id, width, h);
        int col = ImGui.isItemHovered() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ImDrawList draw = ImGui.getWindowDrawList();
        String chevron = isOpen ? Icons.CHEVRON_DOWN : Icons.CHEVRON_R;
        ui.textCentredY(draw, ui.fonts().caption(), x, y, h, col, chevron);
        float tx = x + ui.fonts().body().getFontSize() * BULLET_INDENT_EM;
        ui.textCentredY(draw, ui.fonts().smallMedium(), tx, y, h, col, label);
        return isClicked;
    }

    /** The list under the disclosure, then the line about personal details. */
    void details(Optional<ReportPreview> preview, String privacy, float width) {
        float x = ImGui.getCursorScreenPosX();
        float indent = ui.fonts().body().getFontSize() * BULLET_INDENT_EM;
        if (preview.isEmpty()) {
            ImGui.setCursorScreenPos(x + indent, ImGui.getCursorScreenPosY());
            paragraph(ui.fonts().small(), ImGuiTheme.COL_FG3, LOADING, width - indent);
        } else {
            for (DetailLine line : detailLines(preview.get())) {
                detailLine(line, x, indent, width);
            }
        }
        ImGui.setCursorScreenPos(x, ImGui.getCursorScreenPosY() + ui.m().u(2));
        float y = ImGui.getCursorScreenPosY();
        ImFont font = ui.fonts().small();
        ui.text(ImGui.getWindowDrawList(), ui.fonts().caption(), x, y + (font.getFontSize() * LINE_EM
                - ui.fonts().caption().getFontSize()) * 0.5f, ImGuiTheme.COL_ACCENT, Icons.SHIELD);
        ImGui.setCursorScreenPos(x + indent, y);
        paragraph(font, ImGuiTheme.COL_FG2, privacy, width - indent);
        ImGui.setCursorScreenPos(x, ImGui.getCursorScreenPosY());
    }

    private void detailLine(DetailLine line, float x, float indent, float width) {
        ImFont font = line.isMono() ? ui.fonts().monoCaption() : ui.fonts().small();
        float y = ImGui.getCursorScreenPosY();
        float left = x + indent * (line.isBullet() ? 1f : 2f);
        if (line.isBullet()) {
            ui.text(ImGui.getWindowDrawList(), font, x + indent * BULLET_X,
                    y + (font.getFontSize() * LINE_EM - font.getFontSize()) * 0.5f, ImGuiTheme.COL_FG3, BULLET);
        }
        ImGui.setCursorScreenPos(left, y);
        paragraph(font, line.isMono() ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG, line.text(), x + width - left);
    }

    /**
     * What "What will be sent" lists for {@code preview}: the script's logs by
     * name, the error it stopped with, the launcher's activity and the note.
     * Plain words; the only technical text is the file names and the error line,
     * which are what the user is being shown.
     */
    static List<DetailLine> detailLines(ReportPreview preview) {
        List<DetailLine> lines = new ArrayList<>();
        String script = preview.scriptName();
        if (preview.logFiles().isEmpty()) {
            lines.add(new DetailLine("What " + script + " was doing (no saved logs were found)", false, true));
        } else {
            lines.add(new DetailLine("What " + script + " was doing, from its last "
                    + plural(preview.logFiles().size(), "log", "logs") + ":", false, true));
            preview.logFiles().forEach(f -> lines.add(new DetailLine(f, true, false)));
        }
        preview.crashLine().ifPresentOrElse(
                crash -> {
                    lines.add(new DetailLine("The error it stopped with:", false, true));
                    lines.add(new DetailLine(crash, true, false));
                },
                () -> lines.add(new DetailLine("No error was recorded, so your note matters most", false, true)));
        lines.add(new DetailLine("Recent activity from the BotWithUs launcher", false, true));
        lines.add(new DetailLine("Your two answers above", false, true));
        return List.copyOf(lines);
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : n + " " + many;
    }
}
