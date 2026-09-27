package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;

/**
 * The Advanced Dashboard: headline numbers, every client's script runners, RPC
 * latency pooled across clients, what needs attention and the dev loop, over a
 * dock holding the Console, Logs and Events. One scope, picked in the header,
 * covers all of it.
 */
public final class DashboardPage implements Page {

    /** Line height as a multiple of the font size, the design's {@code --lh}. */
    static final float LINE_HEIGHT = 1.45f;

    private static final float SIDE_COLUMN_EM = 24f;
    /** Below this page width, in body-font ems, the right-hand column stacks under the left. */
    private static final float TWO_COLUMN_MIN_EM = 62f;
    private static final float SCOPE_SELECT_EM = 13f;
    private static final int BODY_STYLE_VARS = 2;

    private final Controls ui;
    private final DashboardModel model;
    private final InstantSource clock;
    private final DashboardState state = new DashboardState();
    private final PanelChrome chrome;
    private final KpiStrip kpis;
    private final RunnersPanel runners;
    private final RpcPanel rpc;
    private final AttentionPanel attention;
    private final DevLoopPanel devLoop;
    private final DashboardDock dock;
    private final ImInt scopeIndex = new ImInt(0);
    /** The view the body drew this frame, which the dock takes its client names from. */
    private DashboardView lastView;
    /** A requested scroll position for the body, 0 to 1, or negative for none. Preview only. */
    private float scrollRequest = -1f;

    public DashboardPage(Controls ui, DashboardModel model, InstantSource clock) {
        this.ui = ui;
        this.model = model;
        this.clock = clock;
        this.chrome = new PanelChrome(ui);
        this.kpis = new KpiStrip(chrome);
        this.runners = new RunnersPanel(chrome);
        this.rpc = new RpcPanel(chrome);
        this.attention = new AttentionPanel(chrome);
        this.devLoop = new DevLoopPanel(chrome);
        this.dock = new DashboardDock(chrome);
    }

    @Override
    public PageId id() {
        return PageId.DASHBOARD;
    }

    /**
     * Brings the Logs tab forward scoped to {@code client}, or to every client
     * when empty: what "View log" on a toast or a card lands on.
     */
    public void openLogs(Optional<ClientKey> client) {
        state.openLogs(client);
    }

    /** What the page shows; a seam for the preview, which stages each state through it. */
    public DashboardState state() {
        return state;
    }

    @Override
    public void render() {
        float w = ImGui.getContentRegionAvailX();
        float h = ImGui.getContentRegionAvailY();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float dockH = dock.height(state, h);
        body(w, h - dockH);
        ImGui.setCursorScreenPos(x, y + h - dockH);
        dock.render(model, lastView, state, w, dockH);
    }

    private void body(float w, float h) {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(4));
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, m.u(4), m.u(4));
        ImGui.beginChild("##dashboard-body", w, Math.max(1f, h), ImGuiChildFlags.AlwaysUseWindowPadding,
                ImGuiWindowFlags.None);
        DashboardView view = model.view(state.scope());
        lastView = view;
        Instant now = clock.instant();
        header(view, now);
        float width = ImGui.getContentRegionAvailX();
        kpis.render(view.kpis(), view.isReset(), width);
        if (width >= ui.fonts().body().getFontSize() * TWO_COLUMN_MIN_EM) {
            twoColumns(view, now, width);
        } else {
            oneColumn(view, now, width);
        }
        ImGui.dummy(0f, 0f);
        if (scrollRequest >= 0f) {
            ImGui.setScrollY(ImGui.getScrollMaxY() * scrollRequest);
        }
        ImGui.endChild();
        ImGui.popStyleVar(BODY_STYLE_VARS);
    }

    private void twoColumns(DashboardView view, Instant now, float width) {
        float gap = ui.m().u(4);
        float sideW = ui.fonts().body().getFontSize() * SIDE_COLUMN_EM;
        float mainW = width - sideW - gap;
        float x = ImGui.getCursorPosX();
        float y = ImGui.getCursorPosY();
        ImGui.beginGroup();
        mainColumn(view, now, mainW);
        ImGui.endGroup();
        float leftBottom = ImGui.getCursorPosY();
        ImGui.setCursorPos(x + mainW + gap, y);
        ImGui.beginGroup();
        sideColumn(view, now, sideW);
        ImGui.endGroup();
        float rightBottom = ImGui.getCursorPosY();
        ImGui.setCursorPos(x, Math.max(leftBottom, rightBottom));
    }

    private void oneColumn(DashboardView view, Instant now, float width) {
        mainColumn(view, now, width);
        sideColumn(view, now, width);
    }

    private void mainColumn(DashboardView view, Instant now, float w) {
        runners.render(view, state, model.actions(), now, w);
        rpc.render(view, state.scope(), scopeLabel(view), w);
    }

    private void sideColumn(DashboardView view, Instant now, float w) {
        attention.render(view, state, model.actions(), now, w);
        devLoop.render(view.devLoop(), model.actions(), w);
    }

    /**
     * Holds the body scrolled to {@code fraction} of the way down, every frame,
     * until asked for a negative one. The dev preview's way to show the panels
     * below the fold, which a user reaches with the scroll wheel.
     */
    void holdScroll(float fraction) {
        scrollRequest = fraction;
    }

    // ── Header ──────────────────────────────────────────────────────────

    /** "Dashboard", when the figures start, the scope picker and Reset metrics. */
    private void header(DashboardView view, Instant now) {
        ImGuiTheme.Metrics m = ui.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float right = x + ImGui.getContentRegionAvailX();
        float h = m.controlHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        String title = "Dashboard";
        ui.textCentredY(draw, ui.fonts().titleMedium(), x, y, h, ImGuiTheme.COL_FG, title);
        String meta = (view.isReset() ? "since reset " : "since start ") + DashFormat.clock(view.since())
                + " · " + DashFormat.elapsed(Duration.between(view.since(), now)) + " ago";
        float metaX = x + ui.width(ui.fonts().titleMedium(), title) + m.u(3);
        ui.textCentredY(draw, ui.fonts().monoCaption(), metaX, y, h, ImGuiTheme.COL_FG2, meta);
        String reset = "Reset metrics";
        float resetW = ui.buttonWidth(Icons.ROTATE_LEFT, reset, Tone.GHOST);
        ImGui.setCursorScreenPos(right - resetW, y);
        if (ui.button("##reset-metrics", Icons.ROTATE_LEFT, reset, Tone.GHOST, true)) {
            model.actions().resetMetrics();
        }
        float selectW = ui.fonts().body().getFontSize() * SCOPE_SELECT_EM;
        ImGui.setCursorScreenPos(right - resetW - m.u(3) - selectW, y);
        scopePicker(view.scopes(), selectW);
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(right - x, h);
    }

    private void scopePicker(List<ScopeOption> scopes, float w) {
        int current = 0;
        for (int i = 0; i < scopes.size(); i++) {
            if (scopes.get(i).scope().equals(state.scope())) {
                current = i;
            }
        }
        scopeIndex.set(current);
        List<String> labels = scopes.stream().map(ScopeOption::display).toList();
        if (ui.select("##scope", scopeIndex, labels, w)) {
            state.setScope(scopes.get(scopeIndex.get()).scope());
        }
    }

    private String scopeLabel(DashboardView view) {
        return view.scopes().stream().filter(o -> o.scope().equals(state.scope())).findFirst()
                .map(ScopeOption::label).orElse("");
    }
}
