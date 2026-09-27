package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.Optional;

/**
 * The page's title row: "Connections", a line on how pipes are being found,
 * and on the right the Auto-connect switch, the pipe prefix, Scan now, and the
 * page's one primary button, "Connect N found", while there is anything found.
 */
final class ConnectionsHeader {

    private static final String TITLE = "Connections";
    private static final String AUTO_CONNECT = "Auto-connect";
    private static final String PREFIX = "Prefix";
    private static final String SCAN = "Scan now";
    private static final String SCANNING = "Scanning…";
    private static final String GLOB = "*";
    private static final int PREFIX_CAPACITY = 128;
    private static final float PREFIX_INPUT_EM = 7.333f;
    private static final float SCANLINE_PX = 2f;
    private static final float SWEEP_FRACTION = 0.3f;
    private static final float SWEEP_PERIOD_S = 1.1f;
    private static final float SWEEP_TRAVEL = 4.4f;
    /** The rule between the switch and the prefix field is 20 px tall at 15 px text. */
    private static final float DIVIDER_EM = 1.333f;

    private final ConnectionWidgets w;
    private final Controls ui;
    private final ConnectionsModel model;
    private final ImString prefix = new ImString(PREFIX_CAPACITY);
    private boolean isEditingPrefix;
    private Optional<String> prefixError = Optional.empty();

    ConnectionsHeader(ConnectionWidgets widgets, ConnectionsModel model) {
        this.w = widgets;
        this.ui = widgets.ui();
        this.model = model;
    }

    /** The header's height, top padding included. */
    float height() {
        ImGuiTheme.Metrics m = ui.m();
        return m.u(4) + m.controlHeight() + m.u(3);
    }

    /** Draws the header across the current window. */
    void render(ConnectionsView view) {
        ImGuiTheme.Metrics m = ui.m();
        float rowH = m.controlHeight();
        float x = ImGui.getWindowPosX() + m.u(5);
        float y = ImGui.getWindowPosY() + m.u(4);
        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.u(5);
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont title = ui.fonts().titleMedium();
        ui.textCentredY(draw, title, x, y, rowH, ImGuiTheme.COL_FG, TITLE);
        float metaX = x + ui.width(title, TITLE) + m.u(3);
        float toolsLeft = tools(view, right, y, rowH);
        meta(draw, view, metaX, y, rowH, toolsLeft - m.u(3) - metaX);
        if (view.isScanning()) {
            scanline(draw, ImGui.getWindowPosX(), y + rowH + m.u(3) - SCANLINE_PX, ImGui.getWindowWidth());
        }
    }

    /** The controls, laid right to left. Returns the x of the leftmost. */
    private float tools(ConnectionsView view, float right, float y, float rowH) {
        ImGuiTheme.Metrics m = ui.m();
        float x = right;
        int found = view.found().size();
        if (found > 0) {
            String label = "Connect " + found + " found";
            x -= ui.buttonWidth(Icons.LINK, label, Tone.PRIMARY);
            ImGui.setCursorScreenPos(x, y);
            if (ui.button("##conn-connect-found", Icons.LINK, label, Tone.PRIMARY, true)) {
                model.connectAllFound();
            }
            x -= m.u(2);
        }
        String scanLabel = view.isScanning() ? SCANNING : SCAN;
        x -= ui.buttonWidth(Icons.SEARCH, scanLabel, Tone.GHOST);
        ImGui.setCursorScreenPos(x, y);
        if (ui.button("##conn-scan", Icons.SEARCH, scanLabel, Tone.GHOST, !view.isScanning())) {
            model.scan();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(view.scanMessage().map(message -> "Last scan: " + message)
                    .orElse("Look for game clients under the prefix now"));
        }
        x = prefixField(view, x - m.u(2), y, rowH);
        return autoConnect(view, x - m.u(3), y, rowH);
    }

    /** The prefix field, ending at {@code right}. Returns its left edge. */
    private float prefixField(ConnectionsView view, float right, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont label = ui.fonts().caption();
        ImFont mono = ui.fonts().monoSmall();
        float inputW = ui.fonts().body().getFontSize() * PREFIX_INPUT_EM;
        float fieldW = m.u(3) + ui.width(label, PREFIX) + m.u(2) + inputW + ui.width(mono, GLOB) + m.u(3);
        float x = right - fieldW;
        if (!isEditingPrefix) {
            prefix.set(view.pipePrefix());
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, right, y + h, ImGuiTheme.COL_BG, m.radius());
        ui.textCentredY(draw, label, x + m.u(3), y, h, ImGuiTheme.COL_FG2, PREFIX);
        float inputX = x + m.u(3) + ui.width(label, PREFIX) + m.u(2);
        if (w.bareInput("##conn-prefix", prefix, mono, inputX, y, inputW, h)) {
            prefixError = model.setPipePrefix(prefix.get());
        }
        isEditingPrefix = ImGui.isItemActive();
        boolean isHovered = ImGui.isItemHovered();
        ui.textCentredY(draw, mono, inputX + inputW, y, h, ImGuiTheme.COL_FG3, GLOB);
        int border = prefixError.isPresent() ? ImGuiTheme.COL_DANGER
                : isEditingPrefix ? ImGuiTheme.COL_FOCUS
                : ImGui.isMouseHoveringRect(x, y, right, y + h) ? ImGuiTheme.COL_BORDER_HOVER : ImGuiTheme.COL_BORDER;
        draw.addRect(x + 0.5f, y + 0.5f, right - 0.5f, y + h - 0.5f, border, m.radius());
        if (isHovered) {
            ImGui.setTooltip(prefixError.orElse("Pipe name prefix to scan for"));
        }
        return x;
    }

    /** The switch and its label, ending at {@code right}, then the divider. Returns the left edge. */
    private float autoConnect(ConnectionsView view, float right, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float dividerX = right - m.hairline();
        float inset = (h - ui.fonts().body().getFontSize() * DIVIDER_EM) * 0.5f;
        draw.addLine(dividerX, y + inset, dividerX, y + h - inset, ImGuiTheme.COL_BORDER, m.hairline());
        ImFont font = ui.fonts().small();
        float labelW = ui.width(font, AUTO_CONNECT);
        float x = dividerX - m.u(3) - labelW - m.u(2) - w.switchWidth();
        ImGui.setCursorScreenPos(x, y);
        if (w.switchToggle("##conn-auto", view.isAutoConnect(), true, h)) {
            model.setAutoConnect(!view.isAutoConnect());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Connect to new BotWithUs pipes automatically and resume their scripts");
        }
        ui.textCentredY(draw, font, x + w.switchWidth() + m.u(2), y, h, ImGuiTheme.COL_FG, AUTO_CONNECT);
        return x;
    }

    /** The dot or spinner and the line beside the title, cut to fit {@code maxW}. */
    private void meta(ImDrawList draw, ConnectionsView view, float x, float y, float h, float maxW) {
        ImGuiTheme.Metrics m = ui.m();
        ImFont font = ui.fonts().monoCaption();
        String text;
        int col = ImGuiTheme.COL_FG2;
        float cy = y + h * 0.5f;
        if (prefixError.isPresent()) {
            text = prefixError.get();
            col = ImGuiTheme.COL_DANGER;
        } else if (view.isScanning()) {
            text = "Scanning " + ConnectionText.pipePath(view.pipePrefix()) + "…";
            w.spinner(draw, x + m.dot(), cy, m.dot(), ImGuiTheme.COL_INFO);
        } else {
            text = ConnectionText.autoConnectLine(view.isAutoConnect(), view.scanInterval());
            draw.addCircleFilled(x + m.dot() * 0.5f, cy, m.dot() * 0.5f,
                    view.isAutoConnect() ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG3);
        }
        float tx = x + m.dot() * 2f + m.u(1.5f);
        if (maxW > tx - x) {
            ui.textCentredY(draw, font, tx, y, h, col, ui.ellipsize(font, text, maxW - (tx - x)));
        }
    }

    /** A thin bar across the page with a segment sweeping along it while a scan runs. */
    private void scanline(ImDrawList draw, float x, float y, float width) {
        draw.addRectFilled(x, y, x + width, y + SCANLINE_PX, ImGuiTheme.COL_ELEVATED);
        float t = (float) ((ImGui.getTime() % SWEEP_PERIOD_S) / SWEEP_PERIOD_S);
        float seg = width * SWEEP_FRACTION;
        float sx = x - seg + seg * SWEEP_TRAVEL * t;
        float s0 = Math.max(x, sx);
        float s1 = Math.min(x + width, sx + seg);
        if (s1 > s0) {
            draw.addRectFilled(s0, y, s1, y + SCANLINE_PX, ImGuiTheme.COL_INFO);
        }
    }
}
