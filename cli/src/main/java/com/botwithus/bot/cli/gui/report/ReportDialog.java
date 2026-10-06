package com.botwithus.bot.cli.gui.report;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.report.ReportFlow.Stage;
import com.botwithus.bot.cli.report.ReportPreview;
import com.botwithus.bot.core.report.ReportReply;
import com.botwithus.bot.core.report.ReportRequest;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.Objects;

/**
 * The "Report a problem" dialog: one modal for the crash toast's "Send report to
 * script author" and the script rows' "Report a problem". Written for someone
 * who has never had to describe a software problem: one optional question, the
 * contents behind a disclosure, one hero button, and the launcher's own words
 * for the outcome, shown as they came.
 *
 * <p>Render thread only. It draws {@link ReportFlow#stage()}; the flow owns
 * the state and the sender owns the waiting.</p>
 */
public final class ReportDialog {

    private static final String POPUP_ID = "##report-problem";
    private static final String QUESTION = "What were you doing when it went wrong? (optional)";
    private static final String WHO_GETS_IT =
            "Your report goes to the person who made this script, so they can fix it.";
    private static final String WHAT_IS_SENT = "What will be sent";
    private static final String PRIVACY = "Your account name, character names, email addresses and passwords "
            + "are removed before anything is sent.";
    private static final String SENDING = "Sending…";
    private static final String SENDING_NOTE = "This can take a minute or two.";
    private static final String SENT_TITLE = "Report sent";
    private static final String FAILED_TITLE = "Your report wasn't sent";
    private static final String CODE_LABEL = "Your report code";

    private static final float WIDTH_EM = 32f;
    private static final float ICON_TILE_EM = 2f;
    private static final float LINE_EM = 1.5f;
    private static final float NOTE_LINES = 4f;
    /** Room for the longest note the website keeps, in UTF-8 bytes (up to four per character). */
    private static final int NOTE_CAPACITY = ReportRequest.MAX_NOTE_CHARS * 4;
    private static final float SPINNER_EM = 1.1f;
    private static final float SPINNER_PERIOD_S = 0.9f;
    private static final float SPINNER_SWEEP = (float) (Math.PI * 1.5);
    private static final float SPINNER_STROKE_PX = 2.5f;
    private static final float SPINNER_TRACK_ALPHA = 0.25f;
    private static final float CODE_BOX_EM = 3.2f;
    private static final float COPIED_FOR_S = 2f;
    private static final int STYLE_VARS = 3;
    private static final int STYLE_COLORS = 3;

    private final Controls ui;
    private final ReportFlow flow;
    private final ImString note = new ImString(NOTE_CAPACITY);
    private final ReportWidgets widgets;
    private boolean isPopupOpen;
    private boolean isDetailsOpen;
    private double copiedAt = Double.NEGATIVE_INFINITY;

    public ReportDialog(Controls ui, ReportFlow flow) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.widgets = new ReportWidgets(ui);
    }

    /** Draws the dialog while the flow has it open. Call once a frame. */
    public void render() {
        Stage stage = flow.stage();
        if (isClosed(stage)) {
            isPopupOpen = false;
            return;
        }
        if (!isPopupOpen) {
            ImGui.openPopup(POPUP_ID);
            isPopupOpen = true;
        }
        place();
        pushStyle();
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.AlwaysAutoResize;
        boolean isOpen = ImGui.beginPopupModal(POPUP_ID, flags);
        popStyle();
        if (!isOpen) {
            return;
        }
        float width = ImGui.getFontSize() * WIDTH_EM;
        switch (stage) {
            case Stage.Composing c -> composing(c, width);
            case Stage.Sending s -> sending(s, width);
            case Stage.Done d -> done(d, width);
            case Stage.Closed _ -> { }
        }
        if (isClosed(flow.stage())) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    /** Opens "What will be sent", as clicking it does; for keyboard-free automation and the preview. */
    public void expandDetails() {
        isDetailsOpen = true;
    }

    private static boolean isClosed(Stage stage) {
        return switch (stage) {
            case Stage.Closed _ -> true;
            case Stage.Composing _, Stage.Sending _, Stage.Done _ -> false;
        };
    }

    // ── Composing ──────────────────────────────────────────────────────────

    private void composing(Stage.Composing c, float width) {
        boolean isFirstFrame = c.isNew();
        if (isFirstFrame) {
            note.set("");
            isDetailsOpen = false;
        }
        header(Icons.FLAG, ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT,
                "Report a problem with " + c.subject().scriptName(), width);
        widgets.paragraph(ui.fonts().small(), ImGuiTheme.COL_FG2, WHO_GETS_IT, width);
        gap();
        widgets.paragraph(ui.fonts().smallMedium(), ImGuiTheme.COL_FG, QUESTION, width);
        boolean isSubmitKey = widgets.noteField("##report-note", note, width,
                ImGui.getFontSize() * LINE_EM * NOTE_LINES, isFirstFrame);
        gap();
        if (widgets.disclosure("##report-details", WHAT_IS_SENT, isDetailsOpen, width)) {
            isDetailsOpen = !isDetailsOpen;
        }
        if (isDetailsOpen) {
            widgets.details(c.previewIfReady(), PRIVACY, width);
        }
        gap();
        flow.drawn();
        composeButtons(width, isSubmitKey);
    }

    private void composeButtons(float width, boolean isSubmitKey) {
        String cancel = "Cancel";
        String send = "Send";
        float gap = ui.m().u(2);
        float total = ui.buttonWidth(null, cancel, Tone.GHOST) + gap + ui.buttonWidth(Icons.PAPER_PLANE, send,
                Tone.PRIMARY);
        float y = ImGui.getCursorScreenPosY();
        float x = ImGui.getCursorScreenPosX() + width - total;
        ImGui.setCursorScreenPos(x, y);
        if (ui.button("##report-cancel", null, cancel, Tone.GHOST, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            flow.close();
            return;
        }
        ImGui.setCursorScreenPos(x + ui.buttonWidth(null, cancel, Tone.GHOST) + gap, y);
        if (ui.button("##report-send", Icons.PAPER_PLANE, send, Tone.PRIMARY, true) || isSubmitKey) {
            flow.send(note.get());
        }
    }

    // ── Sending ────────────────────────────────────────────────────────────

    private void sending(Stage.Sending s, float width) {
        header(Icons.FLAG, ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT,
                "Report a problem with " + s.subject().scriptName(), width);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float fs = ImGui.getFontSize();
        float r = fs * SPINNER_EM * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        drawSpinner(draw, x + r, y + r, r, ImGuiTheme.COL_ACCENT);
        ui.textCentredY(draw, ui.fonts().bodyMedium(), x + r * 2f + ui.m().u(3), y, r * 2f, ImGuiTheme.COL_FG,
                SENDING);
        ImGui.dummy(width, r * 2f);
        gap();
        widgets.paragraph(ui.fonts().small(), ImGuiTheme.COL_FG2, SENDING_NOTE, width);
    }

    private static void drawSpinner(ImDrawList draw, float cx, float cy, float r, int col) {
        draw.addCircle(cx, cy, r, Controls.scaleAlpha(col, SPINNER_TRACK_ALPHA), 0, SPINNER_STROKE_PX);
        float a0 = (float) ((ImGui.getTime() % SPINNER_PERIOD_S) / SPINNER_PERIOD_S * Math.PI * 2);
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, a0, a0 + SPINNER_SWEEP);
        draw.pathStroke(col, 0, SPINNER_STROKE_PX);
    }

    // ── Done ───────────────────────────────────────────────────────────────

    private void done(Stage.Done d, float width) {
        switch (d.reply()) {
            case ReportReply.Sent sent -> {
                header(Icons.CIRCLE_CHECK, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT, SENT_TITLE, width);
                widgets.paragraph(ui.fonts().body(), ImGuiTheme.COL_FG, sent.userMessage(), width);
                gap();
                codeBox(sent.code(), width);
            }
            case ReportReply.Failed failed -> {
                header(Icons.WARNING, ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT, FAILED_TITLE, width);
                widgets.paragraph(ui.fonts().body(), ImGuiTheme.COL_FG, failed.userMessage(), width);
            }
        }
        gap();
        String close = switch (d.reply()) {
            case ReportReply.Sent _ -> "Done";
            case ReportReply.Failed _ -> "Close";
        };
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX() + width - ui.buttonWidth(null, close, Tone.PRIMARY),
                ImGui.getCursorScreenPosY());
        if (ui.button("##report-done", null, close, Tone.PRIMARY, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape, false) || ImGui.isKeyPressed(ImGuiKey.Enter, false)) {
            flow.close();
        }
    }

    /** The code, large, in a tinted box with Copy beside it. */
    private void codeBox(String code, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = ImGui.getFontSize() * CODE_BOX_EM;
        float pad = ui.m().u(4);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_SURFACE, ui.m().radius());
        draw.addRect(x, y, x + width, y + h, ImGuiTheme.COL_BORDER, ui.m().radius());
        ImFont label = ui.fonts().caption();
        ImFont big = ui.fonts().titleMedium();
        float textH = label.getFontSize() * LINE_EM + big.getFontSize();
        float ty = y + (h - textH) * 0.5f;
        ui.text(draw, label, x + pad, ty, ImGuiTheme.COL_FG2, CODE_LABEL);
        ui.text(draw, big, x + pad, ty + label.getFontSize() * LINE_EM, ImGuiTheme.COL_FG, code);
        copyButton(code, x + width - pad, y, h);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, h);
    }

    private void copyButton(String code, float right, float boxY, float boxH) {
        boolean isCopied = ImGui.getTime() - copiedAt < COPIED_FOR_S;
        String icon = isCopied ? Icons.CHECK : Icons.COPY;
        String label = isCopied ? "Copied" : "Copy";
        float bw = ui.buttonWidth(icon, label, Tone.SOFT);
        ImGui.setCursorScreenPos(right - bw, boxY + (boxH - ui.m().controlHeight()) * 0.5f);
        if (ui.button("##report-copy", icon, label, Tone.SOFT, true)) {
            ImGui.setClipboardText(code);
            copiedAt = ImGui.getTime();
        }
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private void header(String icon, int fg, int bg, String title, float width) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float tile = ImGui.getFontSize() * ICON_TILE_EM;
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.iconTile(draw, x, y, tile, icon, fg, bg);
        float textX = x + tile + ui.m().u(3);
        ImFont font = ui.fonts().bodyMedium();
        ui.textCentredY(draw, font, textX, y, tile, ImGuiTheme.COL_FG,
                ui.ellipsize(font, title, x + width - textX));
        ImGui.dummy(width, tile);
        gap();
    }

    private void gap() {
        ImGui.dummy(0f, ui.m().u(2));
    }

    private void place() {
        var vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() * 0.5f, vp.getPosY() + vp.getSizeY() * 0.5f,
                0, 0.5f, 0.5f);
    }

    private void pushStyle() {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(4));
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
}
