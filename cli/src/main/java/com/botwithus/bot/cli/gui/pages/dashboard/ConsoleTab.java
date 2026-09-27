package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.OutputLine;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

/**
 * The dock's Console tab: the command output in JetBrains Mono, and the
 * {@code bwu:<target> ›} prompt with history on the arrow keys and Tab
 * completion. Commands run on the host's command thread, never here.
 */
final class ConsoleTab {

    private static final int INPUT_CAPACITY = 512;
    private static final float PROMPT_EM = 2.4f;
    private static final float PROGRESS_W_EM = 10.667f;
    private static final float PROGRESS_H_EM = 0.4f;
    private static final float BOLD_LIFT = 0.1f;
    private static final float INDETERMINATE_PERIOD_S = 2f;
    private static final String HINT = "Tab completes · ↑↓ history";
    private static final int INPUT_STYLE_VARS = 2;
    private static final int INPUT_STYLE_COLORS = 4;

    private final PanelChrome chrome;
    private final Controls ui;
    private final ImString input = new ImString(INPUT_CAPACITY);
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private boolean isFocusWanted = true;

    ConsoleTab(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
    }

    float promptHeight() {
        return ui.fonts().body().getFontSize() * PROMPT_EM;
    }

    /** The output, filling {@code w} by {@code h} at the cursor. */
    void renderOutput(ConsoleView console, float w, float h) {
        ImGuiTheme.Metrics m = chrome.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(4), m.u(2));
        ImGui.beginChild("##console-out", w, h, ImGuiChildFlags.AlwaysUseWindowPadding,
                ImGuiWindowFlags.HorizontalScrollbar);
        ImGui.popStyleVar();
        boolean wasAtBottom = ImGui.getScrollY() >= ImGui.getScrollMaxY() - 1f;
        ImGui.pushFont(ui.fonts().monoSmall());
        for (OutputLine line : console.lines()) {
            if (!line.isRemoved()) {
                line(line);
            }
        }
        ImGui.popFont();
        if (wasAtBottom) {
            ImGui.setScrollHereY(1f);
        }
        ImGui.endChild();
    }

    private void line(OutputLine line) {
        switch (line.getType()) {
            case TEXT -> text(line);
            case IMAGE -> image(line);
            case PROGRESS -> progress(line);
            case STREAM -> {
                if (line.getLabel() != null) {
                    ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                            "  " + line.getLabel());
                }
                image(line);
            }
        }
    }

    private static void text(OutputLine line) {
        List<OutputLine.Segment> segments = line.getSegments();
        if (segments == null || segments.isEmpty()) {
            ImGui.textUnformatted("");
            return;
        }
        boolean isFirst = true;
        for (OutputLine.Segment seg : segments) {
            if (!isFirst) {
                ImGui.sameLine(0f, 0f);
            }
            isFirst = false;
            float lift = seg.bold() ? BOLD_LIFT : 0f;
            ImGui.textColored(Math.min(seg.r() + lift, 1f), Math.min(seg.g() + lift, 1f),
                    Math.min(seg.b() + lift, 1f), seg.a(), seg.text());
        }
    }

    private static void image(OutputLine line) {
        int tex = line.getTextureId();
        if (tex > 0 && line.getImageWidth() > 0) {
            float w = Math.min(line.getImageWidth(), ImGui.getContentRegionAvailX());
            float scale = w / line.getImageWidth();
            ImGui.image(tex, w, line.getImageHeight() * scale);
        }
    }

    /** "Capturing …" with a slim bar after it, as the design draws a running command. */
    private void progress(OutputLine line) {
        String label = line.getLabel() != null ? line.getLabel() : "Working…";
        ImGui.textUnformatted(label);
        ImGui.sameLine(0f, chrome.m().u(2));
        float fs = ui.fonts().body().getFontSize();
        float w = fs * PROGRESS_W_EM;
        float h = fs * PROGRESS_H_EM;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY() + (ImGui.getTextLineHeight() - h) * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_ELEVATED, h * 0.5f);
        float p = line.getProgress();
        if (p >= 0f) {
            draw.addRectFilled(x, y, x + w * Math.min(1f, p), y + h, ImGuiTheme.COL_INFO, h * 0.5f);
        } else {
            float t = (float) (ImGui.getTime() % INDETERMINATE_PERIOD_S / INDETERMINATE_PERIOD_S);
            float seg = w * 0.3f;
            float sx = x + (w - seg) * t;
            draw.addRectFilled(sx, y, sx + seg, y + h, ImGuiTheme.COL_INFO, h * 0.5f);
        }
        ImGui.dummy(w, ImGui.getTextLineHeight());
    }

    // ── Prompt ──────────────────────────────────────────────────────────

    /** The prompt band, {@code w} wide at the cursor. */
    void renderPrompt(ConsoleView console, DashboardActions actions, float w) {
        ImGuiTheme.Metrics m = chrome.m();
        float h = promptHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y, x + w, y, ImGuiTheme.COL_BORDER, m.hairline());
        ImFont mono = ui.fonts().monoSmall();
        float cx = x + m.u(4);
        String target = console.target().map(pipe -> targetLabel(console, pipe)).orElse("");
        cx = promptLabel(draw, mono, cx, y, h, target);
        float hintW = ui.width(ui.fonts().caption(), HINT);
        ui.textCentredY(draw, ui.fonts().caption(), x + w - m.u(3) - hintW, y, h, ImGuiTheme.COL_FG3, HINT);
        ImGui.setCursorScreenPos(cx, y);
        input(console, actions, x + w - m.u(3) - hintW - m.u(3) - cx, h);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(w, h);
    }

    /** Draws "bwu:<target> ›" (or "bwu ›" with no target) and returns where the input starts. */
    private float promptLabel(ImDrawList draw, ImFont mono, float x, float y, float h, String target) {
        String head = target.isEmpty() ? "bwu" : "bwu:";
        ui.textCentredY(draw, mono, x, y, h, ImGuiTheme.COL_FG2, head);
        float cx = x + ui.width(mono, head);
        if (!target.isEmpty()) {
            ui.textCentredY(draw, mono, cx, y, h, ImGuiTheme.imCol32(ImGuiTheme.CYAN_R, ImGuiTheme.CYAN_G,
                    ImGuiTheme.CYAN_B, 1f), target);
            cx += ui.width(mono, target);
        }
        String tail = " ›";
        ui.textCentredY(draw, mono, cx, y, h, ImGuiTheme.COL_FG2, tail);
        return cx + ui.width(mono, tail) + chrome.m().u(2);
    }

    private static String targetLabel(ConsoleView console, String pipe) {
        return console.targets().stream().filter(o -> o.scope().equals(Scope.of(pipe))).findFirst()
                .map(ScopeOption::label).orElse(pipe);
    }

    private void input(ConsoleView console, DashboardActions actions, float w, float h) {
        ImFont mono = ui.fonts().monoSmall();
        ImGui.pushFont(mono);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, (h - mono.getFontSize()) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f);
        Controls.pushColor(ImGuiCol.FrameBg, 0);
        Controls.pushColor(ImGuiCol.FrameBgHovered, 0);
        Controls.pushColor(ImGuiCol.FrameBgActive, 0);
        Controls.pushColor(ImGuiCol.TextDisabled, ImGuiTheme.COL_FG3);
        if (isFocusWanted) {
            ImGui.setKeyboardFocusHere();
            isFocusWanted = false;
        }
        ImGui.setNextItemWidth(Math.max(1f, w));
        int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.CallbackHistory
                | ImGuiInputTextFlags.CallbackCompletion;
        if (ImGui.inputTextWithHint("##cmd", "help", input, flags)) {
            submit(actions);
        }
        if (ImGui.isItemFocused()) {
            keys(console, actions);
        }
        ImGui.popStyleColor(INPUT_STYLE_COLORS);
        ImGui.popStyleVar(INPUT_STYLE_VARS);
        ImGui.popFont();
    }

    private void submit(DashboardActions actions) {
        String line = input.get().trim();
        if (!line.isEmpty()) {
            history.add(line);
            historyIndex = history.size();
            actions.submit(line);
        }
        input.set("");
        isFocusWanted = true;
    }

    private void keys(ConsoleView console, DashboardActions actions) {
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow) && historyIndex > 0) {
            historyIndex--;
            input.set(history.get(historyIndex));
        }
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow) && historyIndex < history.size()) {
            historyIndex++;
            input.set(historyIndex == history.size() ? "" : history.get(historyIndex));
        }
        if (ImGui.isKeyPressed(ImGuiKey.Tab)) {
            Completion.Result done = Completion.complete(input.get(), console.commands());
            input.set(done.text());
            if (done.matches().size() > 1) {
                actions.note(String.join("  ", done.matches()));
            }
        }
    }

    /** Puts the console's text on the clipboard. */
    static void copy(ConsoleView console) {
        StringBuilder sb = new StringBuilder();
        for (OutputLine line : console.lines()) {
            if (line.isRemoved()) {
                continue;
            }
            switch (line.getType()) {
                case TEXT -> {
                    if (line.getSegments() != null) {
                        line.getSegments().forEach(seg -> sb.append(seg.text()));
                    }
                    sb.append('\n');
                }
                case PROGRESS -> sb.append(line.getLabel() != null ? line.getLabel() : "").append('\n');
                case IMAGE, STREAM -> { }
            }
        }
        ImGui.setClipboardText(sb.toString());
    }
}
