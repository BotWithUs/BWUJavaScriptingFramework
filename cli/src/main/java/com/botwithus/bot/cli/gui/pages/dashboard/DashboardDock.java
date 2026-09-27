package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardState.DockTab;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The dock under the Dashboard: Console, Logs and Events tabs, resizable by
 * dragging its top edge and collapsible to its tab strip.
 */
final class DashboardDock {

    private static final float DEFAULT_EM = 16.5f;
    private static final float MIN_EM = 8f;
    private static final float MAX_FRACTION = 0.7f;
    private static final float TABS_EM = 2.267f;
    private static final float GRIP_PX = 6f;
    private static final float UNDERLINE_PX = 2f;
    private static final float TARGET_SELECT_EM = 12f;
    private static final float BADGE_PAD_EM = 0.333f;
    private static final float BADGE_H_EM = 1.067f;
    private static final float SWITCH_W_EM = 2.133f;
    private static final List<DockTab> TABS = List.of(DockTab.CONSOLE, DockTab.LOGS, DockTab.EVENTS);

    private final PanelChrome chrome;
    private final Controls ui;
    private final ConsoleTab console;
    private final LogsTab logs;
    private final EventsTab events;
    private final ImInt target = new ImInt(0);
    private float heightPx = -1f;

    DashboardDock(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
        this.console = new ConsoleTab(chrome);
        this.logs = new LogsTab(chrome);
        this.events = new EventsTab(chrome);
    }

    float tabsHeight() {
        return ui.fonts().body().getFontSize() * TABS_EM;
    }

    /** How tall the dock is this frame out of {@code available}, the page's height. */
    float height(DashboardState state, float available) {
        if (state.isDockCollapsed()) {
            return tabsHeight();
        }
        float fs = ui.fonts().body().getFontSize();
        if (heightPx < 0f) {
            heightPx = fs * DEFAULT_EM;
        }
        float min = Math.max(tabsHeight(), fs * MIN_EM);
        return Math.max(Math.min(min, available), Math.min(heightPx, available * MAX_FRACTION));
    }

    /** @param view the page's view this frame, for the client names */
    void render(DashboardModel model, DashboardView view, DashboardState state, float w, float h) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.beginChild("##dock", w, h, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleColor();
        ImGui.popStyleVar();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        ImGui.getWindowDrawList().addLine(x, y, x + w, y, ImGuiTheme.COL_BORDER, chrome.m().hairline());
        LogsView logView = model.logs(state.scope(), state.level());
        Function<String, String> labels = labels(view);
        if (!state.isDockCollapsed()) {
            grip(x, y, w);
        }
        tabs(state, logView.errors(), x, y);
        tools(model, state, logView, labels, x + w, y);
        if (!state.isDockCollapsed()) {
            body(model, state, logView, labels, x, y + tabsHeight(), w, h - tabsHeight());
        }
        ImGui.endChild();
    }

    // ── Tab strip ───────────────────────────────────────────────────────

    private void tabs(DashboardState state, int errors, float x, float y) {
        ImGuiTheme.Metrics m = chrome.m();
        float h = tabsHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + h, x + ImGui.getWindowWidth(), y + h, ImGuiTheme.COL_BORDER, m.hairline());
        float cx = x + m.u(4);
        for (DockTab tab : TABS) {
            String label = label(tab);
            float w = ui.width(ui.fonts().smallMedium(), label) + badgeWidth(tab, errors);
            ImGui.setCursorScreenPos(cx, y);
            boolean clicked = ImGui.invisibleButton("##tab-" + tab.name(), w, h);
            boolean isOn = state.tab() == tab;
            int fg = isOn || ImGui.isItemHovered() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
            ui.textCentredY(draw, ui.fonts().smallMedium(), cx, y, h, fg, label);
            if (tab == DockTab.LOGS && errors > 0) {
                badge(draw, cx + ui.width(ui.fonts().smallMedium(), label) + m.u(1.5f), y, h, errors);
            }
            if (isOn) {
                draw.addRectFilled(cx, y + h - UNDERLINE_PX, cx + w, y + h, ImGuiTheme.COL_FG, 1f);
            }
            if (clicked) {
                state.showTab(tab);
            }
            cx += w + m.u(4);
        }
    }

    private static String label(DockTab tab) {
        return switch (tab) {
            case CONSOLE -> "Console";
            case LOGS -> "Logs";
            case EVENTS -> "Events";
        };
    }

    private float badgeWidth(DockTab tab, int errors) {
        if (tab != DockTab.LOGS || errors == 0) {
            return 0f;
        }
        String n = Integer.toString(errors);
        return chrome.m().u(1.5f) + ui.width(ui.fonts().monoCaption(), n)
                + ui.fonts().body().getFontSize() * BADGE_PAD_EM * 2f;
    }

    /** The red count of error lines on the Logs tab. */
    private void badge(ImDrawList draw, float x, float y, float h, int errors) {
        String n = Integer.toString(errors);
        ImFont font = ui.fonts().monoCaption();
        float fs = ui.fonts().body().getFontSize();
        float bh = fs * BADGE_H_EM;
        float bw = ui.width(font, n) + fs * BADGE_PAD_EM * 2f;
        float by = y + (h - bh) * 0.5f;
        draw.addRectFilled(x, by, x + bw, by + bh, ImGuiTheme.COL_DANGER_SOFT, bh * 0.5f);
        ui.textCentredY(draw, font, x + fs * BADGE_PAD_EM, by, bh, ImGuiTheme.COL_DANGER, n);
    }

    // ── Tools, right to left ────────────────────────────────────────────

    private void tools(DashboardModel model, DashboardState state, LogsView logView,
                       Function<String, String> labels, float right, float y) {
        ImGuiTheme.Metrics m = chrome.m();
        float s = chrome.iconButtonSize();
        float by = y + (tabsHeight() - s) * 0.5f;
        float cx = right - m.u(3) - s;
        ImGui.setCursorScreenPos(cx, by);
        boolean isCollapsed = state.isDockCollapsed();
        if (chrome.iconButton("##collapse", isCollapsed ? Icons.CHEVRON_UP : Icons.CHEVRON_DOWN, ImGuiTheme.COL_FG2,
                ImGuiTheme.COL_ELEVATED, true, isCollapsed ? "Open the dock" : "Collapse the dock")) {
            state.setDockCollapsed(!isCollapsed);
        }
        cx -= s + m.u(1);
        ImGui.setCursorScreenPos(cx, by);
        if (chrome.iconButton("##copy", Icons.COPY, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED, true,
                "Copy " + label(state.tab()).toLowerCase(Locale.ROOT))) {
            copy(model, state, logView);
        }
        float toolsRight = cx - m.u(2);
        switch (state.tab()) {
            case CONSOLE -> targetPicker(model, model.console(), toolsRight, y);
            case LOGS -> logTools(model, state, toolsRight, y);
            case EVENTS -> eventsCaption(state, labels, toolsRight, y);
        }
    }

    private void copy(DashboardModel model, DashboardState state, LogsView logView) {
        switch (state.tab()) {
            case CONSOLE -> ConsoleTab.copy(model.console());
            case LOGS -> LogsTab.copy(logView);
            case EVENTS -> EventsTab.copy(model.events(state.scope()));
        }
    }

    private void targetPicker(DashboardModel model, ConsoleView view, float right, float y) {
        if (view.targets().isEmpty()) {
            return;
        }
        List<String> labels = view.targets().stream().map(ScopeOption::label).toList();
        int current = 0;
        for (int i = 0; i < view.targets().size(); i++) {
            if (view.target().map(Scope::of).equals(Optional.of(view.targets().get(i).scope()))) {
                current = i;
            }
        }
        target.set(current);
        float w = ui.fonts().body().getFontSize() * TARGET_SELECT_EM;
        ImGui.setCursorScreenPos(right - w, y + (tabsHeight() - chrome.m().controlHeight()) * 0.5f);
        if (ui.select("##console-target", target, labels, w)) {
            view.targets().get(target.get()).scope().client().ifPresent(model.actions()::setConsoleTarget);
        }
    }

    private void logTools(DashboardModel model, DashboardState state, float right, float y) {
        ImGuiTheme.Metrics m = chrome.m();
        float s = chrome.iconButtonSize();
        float cx = right - s;
        ImGui.setCursorScreenPos(cx, y + (tabsHeight() - s) * 0.5f);
        if (chrome.iconButton("##clear", Icons.TRASH, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED, true,
                "Clear the log")) {
            model.actions().clearLogs();
        }
        String follow = "Follow";
        float followW = ui.width(ui.fonts().small(), follow) + m.u(2) + ui.fonts().body().getFontSize() * SWITCH_W_EM;
        cx -= m.u(2) + followW;
        ImGui.setCursorScreenPos(cx, y + (tabsHeight() - m.controlHeight()) * 0.5f);
        if (ui.toggleRow("##follow", follow, state.isFollowing(), followW)) {
            state.setFollowing(!state.isFollowing());
        }
        cx -= m.u(3);
        LogLevel[] levels = LogLevel.values();
        for (int i = levels.length - 1; i >= 0; i--) {
            cx -= chrome.pillWidth(levels[i].name());
            ImGui.setCursorScreenPos(cx, y + (tabsHeight() - chrome.pillHeight()) * 0.5f);
            if (chrome.pill("##lvl-" + levels[i].name(), levels[i].name(), state.level() == levels[i])) {
                state.setLevel(levels[i]);
            }
            cx -= m.u(0.5f);
        }
    }

    private void eventsCaption(DashboardState state, Function<String, String> labels, float right, float y) {
        String text = "Event bus, " + state.scope().client().map(labels).orElse("all clients");
        ImFont font = ui.fonts().caption();
        ui.textCentredY(ImGui.getWindowDrawList(), font, right - ui.width(font, text), y, tabsHeight(),
                ImGuiTheme.COL_FG2, text);
    }

    // ── Body and grip ───────────────────────────────────────────────────

    private void body(DashboardModel model, DashboardState state, LogsView logView,
                      Function<String, String> labels, float x, float y, float w, float h) {
        ImGui.setCursorScreenPos(x, y);
        switch (state.tab()) {
            case CONSOLE -> {
                ConsoleView consoleView = model.console();
                float promptH = console.promptHeight();
                console.renderOutput(consoleView, w, Math.max(1f, h - promptH));
                ImGui.setCursorScreenPos(x, y + h - promptH);
                console.renderPrompt(consoleView, model.actions(), w);
            }
            case LOGS -> logs.render(logView, state, labels, w, h);
            case EVENTS -> events.render(model.events(state.scope()), state, w, h);
        }
    }

    /** A client's name as the page's scope picker knows it, else its pipe. */
    private static Function<String, String> labels(DashboardView view) {
        return pipe -> view.scopes().stream()
                .filter(o -> o.scope().equals(Scope.of(pipe))).findFirst().map(ScopeOption::label).orElse(pipe);
    }

    /** The dock's top edge: drag to resize. Submitted before the tabs, so it wins the overlap. */
    private void grip(float x, float y, float w) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##dock-grip", w, GRIP_PX);
        boolean isActive = ImGui.isItemActive();
        if (ImGui.isItemHovered() || isActive) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeNS);
            ImGui.getWindowDrawList().addRectFilled(x, y, x + w, y + GRIP_PX, ImGuiTheme.COL_INFO_SOFT);
        }
        if (isActive) {
            heightPx -= ImGui.getIO().getMouseDeltaY();
        }
    }
}
