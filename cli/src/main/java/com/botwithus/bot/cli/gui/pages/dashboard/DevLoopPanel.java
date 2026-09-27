package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * "Dev loop": the scripts folder, what the last load pass found, the Reload
 * button, and the Watch and Restart-after-reload switches, which are the same
 * settings the Settings page and {@code config set} change.
 */
final class DevLoopPanel {

    private static final float SWITCH_W_EM = 2.133f;
    private static final float SPINNER_R_EM = 0.4f;
    private static final float SPINNER_TURN_S = 0.9f;
    private static final float SPINNER_ARC = (float) (Math.PI * 1.5);
    private static final float SPINNER_STROKE_EM = 0.133f;
    private static final int SPINNER_SEGMENTS = 16;
    /** The widget kit's gap between a button's icon and its label. */
    private static final float ICON_GAP_EM = 0.467f;

    /** One key/value line; {@code color} is the value's. */
    private record Line(String key, String value, int color) { }

    private final PanelChrome chrome;
    private final Controls ui;

    DevLoopPanel(PanelChrome chrome) {
        this.chrome = chrome;
        this.ui = chrome.ui();
    }

    void render(DevLoopView dev, DashboardActions actions, float width) {
        chrome.begin("##devloop", width);
        chrome.header("Dev loop", dev.scriptsFolder());
        ImGuiTheme.Metrics m = chrome.m();
        float x = ImGui.getWindowPosX() + m.u(4);
        float right = ImGui.getWindowPosX() + width - m.u(4);
        float y = ImGui.getCursorScreenPosY() + m.u(3);
        y = lines(lines(dev), x, right, y) + m.u(3);
        y = controls(dev, actions, x, right, y);
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), y + m.u(3));
        ImGui.dummy(0f, 0f);
        chrome.end();
    }

    private static List<Line> lines(DevLoopView dev) {
        return List.of(watcher(dev), lastReload(dev), jars(dev),
                new Line("After a reload", dev.isRestartAfterReload() ? "start what was running" : "leave it stopped",
                        ImGuiTheme.COL_FG));
    }

    private static Line watcher(DevLoopView dev) {
        if (dev.isWatcherRunning()) {
            return new Line("Watcher", "watching " + dev.scriptsFolder(), ImGuiTheme.COL_ACCENT);
        }
        return dev.isWatchOn()
                ? new Line("Watcher", "on · not running", ImGuiTheme.COL_WARN)
                : new Line("Watcher", "off", ImGuiTheme.COL_FG);
    }

    private static Line lastReload(DevLoopView dev) {
        String value = dev.lastReload()
                .map(at -> DashFormat.clock(at) + " · " + dev.scriptsLoaded() + " scripts on " + dev.clients()
                        + (dev.clients() == 1 ? " client" : " clients"))
                .orElse("not yet this session");
        return new Line("Last reload", value, ImGuiTheme.COL_FG);
    }

    private static Line jars(DevLoopView dev) {
        if (dev.jarsFailed() > 0) {
            return new Line("JARs", dev.jarsLoaded() + " loaded · " + dev.jarsFailed() + " failed",
                    ImGuiTheme.COL_DANGER);
        }
        return dev.jarsLoaded() > 0
                ? new Line("JARs", dev.jarsLoaded() + " loaded", ImGuiTheme.COL_ACCENT)
                : new Line("JARs", "none loaded", ImGuiTheme.COL_FG2);
    }

    private float lines(List<Line> lines, float x, float right, float y) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont keyFont = ui.fonts().caption();
        ImFont valueFont = ui.fonts().monoCaption();
        float lineH = keyFont.getFontSize() * DashboardPage.LINE_HEIGHT + chrome.m().u(1);
        float cy = y;
        for (Line line : lines) {
            ui.text(draw, keyFont, x, cy, ImGuiTheme.COL_FG2, line.key());
            float valueX = x + ui.width(keyFont, line.key()) + chrome.m().u(4);
            String value = ui.ellipsize(valueFont, line.value(), right - valueX);
            ui.text(draw, valueFont, right - ui.width(valueFont, value), cy, line.color(), value);
            cy += lineH;
        }
        return cy;
    }

    /** Reload, then the two switches, wrapping to a new row when they do not fit. */
    private float controls(DevLoopView dev, DashboardActions actions, float x, float right, float y) {
        float h = chrome.m().controlHeight();
        ImGui.setCursorScreenPos(x, y);
        reloadButton(dev, actions);
        float cx = x + ui.buttonWidth(Icons.ROTATE, reloadLabel(dev), Tone.PRIMARY) + chrome.m().u(3);
        float cy = y;
        float watchW = switchWidth("Watch");
        if (cx + watchW > right) {
            cx = x;
            cy += h + chrome.m().u(2);
        }
        ImGui.setCursorScreenPos(cx, cy);
        if (ui.toggleRow("##watch", "Watch", dev.isWatchOn(), watchW)) {
            actions.setWatch(!dev.isWatchOn());
        }
        cx += watchW + chrome.m().u(3);
        float restartW = switchWidth("Restart after reload");
        if (cx + restartW > right) {
            cx = x;
            cy += h + chrome.m().u(2);
        }
        ImGui.setCursorScreenPos(cx, cy);
        if (ui.toggleRow("##restart", "Restart after reload", dev.isRestartAfterReload(), restartW)) {
            actions.setRestartAfterReload(!dev.isRestartAfterReload());
        }
        return cy + h;
    }

    private float switchWidth(String label) {
        return ui.width(ui.fonts().small(), label) + chrome.m().u(2)
                + ui.fonts().body().getFontSize() * SWITCH_W_EM;
    }

    private static String reloadLabel(DevLoopView dev) {
        return dev.isReloading() ? "Reloading…" : "Reload scripts";
    }

    private void reloadButton(DevLoopView dev, DashboardActions actions) {
        if (!dev.isReloading()) {
            if (ui.button("##reload", Icons.ROTATE, reloadLabel(dev), Tone.PRIMARY, true)) {
                actions.reload();
            }
            return;
        }
        busyButton(reloadLabel(dev));
    }

    /**
     * The primary button while its work runs: faded, not clickable, with a
     * turning arc where the icon was, so the click visibly landed.
     */
    private void busyButton(String label) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fs = ui.fonts().body().getFontSize();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ui.buttonWidth(Icons.ROTATE, label, Tone.PRIMARY);
        float h = chrome.m().controlHeight();
        draw.addRectFilled(x, y, x + w, y + h, Controls.scaleAlpha(ImGuiTheme.COL_ACCENT, ImGuiTheme.DISABLED_ALPHA),
                chrome.m().radius());
        float iconW = ui.width(ui.fonts().small(), Icons.ROTATE);
        float cx = x + chrome.m().u(3) + iconW * 0.5f;
        float r = fs * SPINNER_R_EM;
        float a0 = (float) (ImGui.getTime() / SPINNER_TURN_S * Math.PI * 2.0);
        draw.pathClear();
        draw.pathArcTo(cx, y + h * 0.5f, r, a0, a0 + SPINNER_ARC, SPINNER_SEGMENTS);
        draw.pathStroke(ImGuiTheme.COL_ON_ACCENT, 0, fs * SPINNER_STROKE_EM);
        float lx = x + chrome.m().u(3) + iconW + fs * ICON_GAP_EM;
        ui.textCentredY(draw, ui.fonts().smallMedium(), lx, y, h, ImGuiTheme.COL_ON_ACCENT, label);
        ImGui.dummy(w, h);
    }
}
