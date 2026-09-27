package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * "Needs attention": what stopped against the user's will, or soon will, each
 * with the one or two actions that deal with it and, where there is one, the
 * stack trace behind a toggle.
 */
final class AttentionPanel {

    private static final float EMPTY_EM = 5.6f;
    private static final float ICON_COL_EM = 1.067f;
    private static final float TRACE_MAX_EM = 10f;
    private static final int TRACE_STYLE_VARS = 3;
    private static final int TRACE_STYLE_COLORS = 2;
    private static final String TRACE_INDENT = "  ";

    /** One button under an entry. */
    private record Act(String label, Runnable run) { }

    /** An entry as drawn. */
    private record Entry(String icon, int color, String title, String detail, List<Act> acts, String trace) { }

    private final PanelChrome chrome;
    private final Controls ui;

    AttentionPanel(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
    }

    void render(DashboardView view, DashboardState state, DashboardActions actions, Instant now, float width) {
        chrome.begin("##attention", width);
        List<AttentionItem> items = view.attention();
        chrome.header("Needs attention", items.isEmpty() ? "" : items.size() + " open");
        if (items.isEmpty()) {
            empty(state.scope(), view);
        }
        for (int i = 0; i < items.size(); i++) {
            AttentionItem item = items.get(i);
            if (i > 0) {
                chrome.rule(ImGui.getCursorScreenPosY());
            }
            ImGui.pushID(item.key());
            entry(item, entry(item, state, actions, now), state);
            ImGui.popID();
        }
        chrome.end();
    }

    private void empty(Scope scope, DashboardView view) {
        String where = scope.client().map(key -> " on " + labelOf(view, scope, key)).orElse("");
        String text = "Nothing needs attention" + where + ".";
        ImFont font = ui.fonts().small();
        ImFont icons = ui.fonts().caption();
        float h = ui.fonts().body().getFontSize() * EMPTY_EM;
        float w = ImGui.getWindowWidth();
        float iconW = ui.width(icons, Icons.CHECK) + chrome.m().u(1.5f);
        float x = ImGui.getWindowPosX() + (w - iconW - ui.width(font, text)) * 0.5f;
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.textCentredY(draw, icons, x, y, h, ImGuiTheme.COL_ACCENT, Icons.CHECK);
        ui.textCentredY(draw, font, x + iconW, y, h, ImGuiTheme.COL_FG2, text);
        ImGui.dummy(w, h);
    }

    private static String labelOf(DashboardView view, Scope scope, ClientKey key) {
        return view.scopes().stream().filter(o -> o.scope().equals(scope)).findFirst()
                .map(ScopeOption::label).orElse(key.value());
    }

    // ── Content per kind ────────────────────────────────────────────────

    private Entry entry(AttentionItem item, DashboardState state, DashboardActions actions, Instant now) {
        return switch (item) {
            case AttentionItem.Stalled s -> new Entry(Icons.HOURGLASS, ImGuiTheme.COL_WARN,
                    s.ref().script() + " stalled on " + s.clientLabel(),
                    "Liveness STALLED" + s.since().map(t -> " · " + DashFormat.span(Duration.between(t, now))
                            + " inside onLoop()").orElse("") + " · iteration " + DashFormat.count(s.iteration()),
                    List.of(new Act("Stop", () -> actions.stop(s.ref())),
                            new Act("Thread dump", () -> dump(s.ref(), state, actions))), "");
            case AttentionItem.Crashed c -> new Entry(Icons.CIRCLE_XMARK, ImGuiTheme.COL_DANGER,
                    c.ref().script() + " crashed on " + c.clientLabel(),
                    EventRows.crash(c.crash()) + " · iteration " + DashFormat.count(c.crash().iteration())
                            + " · " + c.total() + " total",
                    List.of(new Act("Restart", () -> actions.run(c.ref())), copy(c.trace())), c.trace());
            case AttentionItem.CutOff c -> new Entry(Icons.BAN, ImGuiTheme.COL_DANGER,
                    c.ref().script() + " cut off on " + c.clientLabel(),
                    "Ignored a stop and lost its game access (" + c.liveness() + "). Restart the host to run it.",
                    List.of(new Act("Thread dump", () -> dump(c.ref(), state, actions))), "");
            case AttentionItem.NotResponding n -> new Entry(Icons.PLUG_EXCLAMATION, ImGuiTheme.COL_WARN,
                    n.clientLabel() + " not responding", notResponding(n, now),
                    List.of(new Act("Reconnect now", () -> actions.retryNow(n.clientKey()))), "");
            case AttentionItem.GaveUp g -> new Entry(Icons.PLUG_XMARK, ImGuiTheme.COL_DANGER,
                    g.clientLabel() + " stopped reconnecting",
                    g.pipe() + " · gave up after " + g.attempts() + " attempts",
                    List.of(new Act("Try again", () -> actions.retryNow(g.clientKey()))), "");
            case AttentionItem.LoadFailed f -> new Entry(Icons.FILE_XMARK, ImGuiTheme.COL_DANGER,
                    fileName(f.jar()) + " failed to load", f.error(),
                    List.of(new Act("Reload", actions::reload), copy(f.trace())), f.trace());
        };
    }

    private static String notResponding(AttentionItem.NotResponding n, Instant now) {
        StringBuilder sb = new StringBuilder(n.pipe());
        n.since().ifPresent(t -> sb.append(" · no reply for ").append(DashFormat.elapsed(Duration.between(t, now))));
        if (n.attempt() > 0) {
            sb.append(" · attempt ").append(n.attempt()).append(", next in ").append(DashFormat.delay(n.nextDelayMs()));
        }
        return sb.toString();
    }

    private static Act copy(String trace) {
        return new Act("Copy trace", () -> ImGui.setClipboardText(trace));
    }

    private static void dump(RunnerRef ref, DashboardState state, DashboardActions actions) {
        actions.threadDump(ref);
        state.showTab(DashboardState.DockTab.CONSOLE);
    }

    private static String fileName(Path jar) {
        Path name = jar.getFileName();
        return name != null ? name.toString() : jar.toString();
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    private void entry(AttentionItem item, Entry e, DashboardState state) {
        ImGuiTheme.Metrics m = chrome.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x0 = ImGui.getWindowPosX() + m.u(4);
        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.u(3);
        float y = ImGui.getCursorScreenPosY() + m.u(3);
        float textX = x0 + ui.fonts().body().getFontSize() * ICON_COL_EM + m.u(2);
        float titleH = ui.fonts().small().getFontSize() * DashboardPage.LINE_HEIGHT;
        ui.textCentredY(draw, ui.fonts().caption(), x0, y, titleH, e.color(), e.icon());
        String time = item.since().map(DashFormat::clock).orElse("");
        float timeW = ui.width(ui.fonts().monoCaption(), time);
        ui.textCentredY(draw, ui.fonts().monoCaption(), right - timeW, y, titleH, ImGuiTheme.COL_FG3, time);
        String title = ui.ellipsize(ui.fonts().smallMedium(), e.title(), right - timeW - m.u(2) - textX);
        ui.textCentredY(draw, ui.fonts().smallMedium(), textX, y, titleH, ImGuiTheme.COL_FG, title);
        y += titleH;
        float detailH = ui.fonts().monoCaption().getFontSize() * DashboardPage.LINE_HEIGHT;
        for (String line : ui.wrap(ui.fonts().monoCaption(), e.detail(), right - textX)) {
            ui.text(draw, ui.fonts().monoCaption(), textX, y, ImGuiTheme.COL_FG2, line);
            y += detailH;
        }
        y = buttons(e, item, state, textX, y + m.u(1));
        if (!e.trace().isEmpty() && state.isExpanded(item.key())) {
            y = trace(e.trace(), x0 + m.u(6), right, y + m.u(3));
        }
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), y + m.u(3));
        ImGui.dummy(0f, 0f);
    }

    /** The entry's buttons in a row, then the trace toggle; returns the y below them. */
    private float buttons(Entry e, AttentionItem item, DashboardState state, float x, float y) {
        float h = chrome.m().controlSmallHeight();
        float cx = x;
        for (Act act : e.acts()) {
            ImGui.setCursorScreenPos(cx, y);
            if (ui.button("##" + act.label(), null, act.label(), Tone.GHOST, true, h)) {
                act.run().run();
            }
            cx += ui.buttonWidth(null, act.label(), Tone.GHOST) + chrome.m().u(1);
        }
        if (!e.trace().isEmpty()) {
            boolean isOpen = state.isExpanded(item.key());
            ImGui.setCursorScreenPos(cx, y);
            String label = isOpen ? "Hide" : "Stack trace";
            if (chrome.textButton("##trace", isOpen ? Icons.CHEVRON_UP : Icons.CHEVRON_DOWN, label, h)) {
                state.toggleExpanded(item.key());
            }
        }
        return y + h;
    }

    /** The stack trace in a sunken mono box, scrolling past a set height; returns the y below it. */
    private float trace(String trace, float x, float right, float y) {
        ImGuiTheme.Metrics m = chrome.m();
        List<String> lines = trace.lines().toList();
        ImFont font = ui.fonts().monoCaption();
        float lineH = font.getFontSize() * DashboardPage.LINE_HEIGHT;
        float h = Math.min(lines.size() * lineH + m.u(2) * 2f, ui.fonts().body().getFontSize() * TRACE_MAX_EM);
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(3), m.u(2));
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, m.radius());
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, m.hairline());
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_BG);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        ImGui.beginChild("##trace-box", right - x, h,
                ImGuiChildFlags.Border | ImGuiChildFlags.AlwaysUseWindowPadding, ImGuiWindowFlags.HorizontalScrollbar);
        ImGui.popStyleColor(TRACE_STYLE_COLORS);
        ImGui.popStyleVar(TRACE_STYLE_VARS);
        ImGui.pushFont(font);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, lineH - font.getFontSize());
        for (int i = 0; i < lines.size(); i++) {
            Controls.pushColor(ImGuiCol.Text, traceColor(i, lines.get(i)));
            ImGui.textUnformatted(lines.get(i).replace("\t", TRACE_INDENT));
            ImGui.popStyleColor();
        }
        ImGui.popStyleVar();
        ImGui.popFont();
        ImGui.endChild();
        return y + h;
    }

    private static int traceColor(int index, String line) {
        if (index == 0) {
            return ImGuiTheme.COL_DANGER;
        }
        return ownFrame(line) ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
    }

    /** A frame in the script's own code rather than the host's or the JDK's, drawn brighter. */
    private static boolean ownFrame(String line) {
        String t = line.strip();
        return t.startsWith("at ") && !t.startsWith("at java.") && !t.startsWith("at com.botwithus.bot.")
                && !t.startsWith("at jdk.") && !t.startsWith("at sun.");
    }
}
