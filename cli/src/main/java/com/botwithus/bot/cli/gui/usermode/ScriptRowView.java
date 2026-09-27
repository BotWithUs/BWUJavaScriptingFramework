package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.usermode.CardText.Meta;
import com.botwithus.bot.cli.gui.usermode.CardWidgets.IconTint;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;
import com.botwithus.bot.cli.gui.usermode.board.ScriptState;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;

/**
 * One script row on a client card: an icon tile tinted by the script's state,
 * its name and version over a meta line, the actions its state allows, and,
 * while it runs, its pulse lane underneath.
 */
final class ScriptRowView {

    private static final float NAME_LINE = 1.3f;
    private static final float META_LINE = 1.45f;
    /** The round-2 lane height, 18 px at a 15 px body. */
    private static final float LANE_EM = 1.2f;

    /** What a row button does. */
    private enum Kind { SETTINGS, STOP, RUN, RESTART, LOG }

    /** One action button on a row. */
    private record Action(Kind kind, String icon, IconTint tint, String tooltip, boolean enabled) { }

    private final Controls ui;
    private final CardWidgets widgets;
    private final PulseLaneView lane;

    ScriptRowView(Controls ui, CardWidgets widgets) {
        this.ui = ui;
        this.widgets = widgets;
        this.lane = new PulseLaneView(ui);
    }

    /** Height of {@code row} on a card {@code w} wide. */
    float height(ClientView view, ScriptRow row, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float block = Math.max(m.iconTile(), textHeight(view, row, textWidth(view, row, w)));
        float laneBlock = row.state().isRunning() && view.state().isConnected() ? m.u(2) + laneHeight() : 0f;
        return m.u(3) + block + laneBlock + m.u(3);
    }

    /**
     * Draws {@code row} with its top-left at ({@code x}, {@code y}).
     *
     * @return what the page should open: the inspector when its Settings was
     *         clicked, Management when its robot link was
     */
    ClientCard.Intent render(ClientView view, ScriptRow row, float x, float y, float w, ClientActions actions) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float tile = m.iconTile();
        float textW = textWidth(view, row, w);
        float block = Math.max(tile, textHeight(view, row, textW));
        float left = x + m.u(4);
        float top = y + m.u(3);
        CardTone tone = tileTone(row.state(), view.state());
        ui.iconTile(draw, left, top + (block - tile) * 0.5f, tile,
                CategoryStyle.of(row.script().category()).icon(), tone.fg(), tone.bg());
        float textX = left + tile + m.u(3);
        float textY = top + (block - textHeight(view, row, textW)) * 0.5f;
        paintText(draw, view, row, textX, textY, textW);
        boolean isManagement = managedLink(view, row, textX, textY, textW);
        boolean isConfigure = paintActions(view, row, x + w - m.u(3), top + (block - m.controlHeight()) * 0.5f,
                actions);
        if (row.state().isRunning() && view.state().isConnected()) {
            float laneY = top + block + m.u(2);
            lane.running(draw, textX, laneY, x + w - m.u(3) - textX, laneHeight(), row.laneNanos(),
                    row.avgLoopMs());
        }
        if (isConfigure) {
            return new ClientCard.Intent.Configure(row.name());
        }
        return isManagement ? new ClientCard.Intent.OpenManagement(row.managedBy().orElseThrow())
                : new ClientCard.Intent.None();
    }

    private float laneHeight() {
        return ui.fonts().body().getFontSize() * LANE_EM;
    }

    // ── Text ───────────────────────────────────────────────────────────────

    private float textWidth(ClientView view, ScriptRow row, float w) {
        ImGuiTheme.Metrics m = ui.m();
        List<Action> buttons = actionsFor(view, row);
        float actionsW = buttons.isEmpty() ? 0f
                : buttons.size() * m.controlHeight() + (buttons.size() - 1) * m.u(1) + m.u(3);
        return Math.max(1f, w - m.u(4) - m.iconTile() - m.u(3) - actionsW - m.u(3));
    }

    private float textHeight(ClientView view, ScriptRow row, float textW) {
        ImFont nameFont = ui.fonts().smallMedium();
        return nameFont.getFontSize() * NAME_LINE + metaLines(view, row, textW).size() * metaLineHeight();
    }

    private float metaLineHeight() {
        return ui.fonts().monoCaption().getFontSize() * META_LINE;
    }

    private List<String> metaLines(ClientView view, ScriptRow row, float textW) {
        Meta meta = CardText.meta(row, view.state(), view.resume());
        ImFont font = ui.fonts().monoCaption();
        return meta.wraps() ? ui.wrap(font, meta.text(), textW) : List.of(ui.ellipsize(font, meta.text(), textW));
    }

    private void paintText(ImDrawList draw, ClientView view, ScriptRow row, float x, float y, float w) {
        ImFont nameFont = ui.fonts().smallMedium();
        ImFont mono = ui.fonts().monoCaption();
        ScriptInfo script = row.script();
        String version = script.version().isBlank() ? "" : "v" + script.version();
        float versionW = version.isEmpty() ? 0f : ui.m().u(1) + ui.width(mono, version);
        String name = ui.ellipsize(nameFont, script.name(), Math.max(1f, w - versionW));
        float nameH = nameFont.getFontSize() * NAME_LINE;
        ui.text(draw, nameFont, x, y, ImGuiTheme.COL_FG, name);
        if (!version.isEmpty()) {
            float vx = x + ui.width(nameFont, name) + ui.m().u(1);
            ui.text(draw, mono, vx, y + nameFont.getFontSize() - mono.getFontSize(), ImGuiTheme.COL_FG2, version);
        }
        Meta meta = CardText.meta(row, view.state(), view.resume());
        float lineY = y + nameH;
        for (String line : metaLines(view, row, w)) {
            ui.text(draw, mono, x, lineY + (metaLineHeight() - mono.getFontSize()) * 0.5f, meta.tone().fg(), line);
            lineY += metaLineHeight();
        }
    }

    /** The name line's width before the robot link: the name and its version. */
    private float nameLineWidth(ScriptRow row, float w) {
        ImFont nameFont = ui.fonts().smallMedium();
        ImFont mono = ui.fonts().monoCaption();
        ScriptInfo script = row.script();
        String version = script.version().isBlank() ? "" : "v" + script.version();
        float versionW = version.isEmpty() ? 0f : ui.m().u(1) + ui.width(mono, version);
        String name = ui.ellipsize(nameFont, script.name(), Math.max(1f, w - versionW));
        return ui.width(nameFont, name) + versionW;
    }

    /**
     * The blue robot link after the version, "🤖 Break Scheduler", when a
     * management script manages this script on this client; returns whether
     * it was clicked.
     */
    private boolean managedLink(ClientView view, ScriptRow row, float x, float y, float w) {
        if (row.managedBy().isEmpty()) {
            return false;
        }
        String manager = row.managedBy().get();
        ImFont cap = ui.fonts().caption();
        float lx = x + nameLineWidth(row, w) + ui.m().u(2);
        float glyphW = ui.width(cap, Icons.ROBOT) + ui.m().u(1);
        float room = x + w - lx - glyphW;
        if (room <= 0f) {
            return false;
        }
        String label = ui.ellipsize(cap, manager, room);
        float h = ui.fonts().smallMedium().getFontSize() * NAME_LINE;
        float linkW = glyphW + ui.width(cap, label);
        ImGui.setCursorScreenPos(lx, y);
        boolean isClicked = ImGui.invisibleButton("mgd##" + view.id().value() + "/" + row.name(), linkW, h);
        boolean isHovered = ImGui.isItemHovered();
        if (isHovered) {
            ImGui.setTooltip("Managed by " + manager + ". Open Management.");
        }
        int col = isHovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_INFO;
        ImDrawList draw = ImGui.getWindowDrawList();
        ui.textCentredY(draw, cap, lx, y, h, col, Icons.ROBOT);
        ui.textCentredY(draw, cap, lx + glyphW, y, h, col, label);
        return isClicked;
    }

    private static CardTone tileTone(ScriptState state, ClientState client) {
        return switch (state) {
            case ScriptState.Running _ -> CardTone.RUN;
            case ScriptState.Stopped _ -> CardTone.IDLE;
            case ScriptState.Stalled _ -> CardTone.WARN;
            case ScriptState.Crashed _, ScriptState.CutOff _ -> CardTone.ERR;
            case ScriptState.Waiting _ -> switch (client) {
                case ClientState.Resuming _ -> CardTone.INFO;
                case ClientState.Identifying _, ClientState.Connected _, ClientState.NotResponding _,
                     ClientState.Closed _ -> CardTone.IDLE;
            };
        };
    }

    // ── Actions ────────────────────────────────────────────────────────────

    /** @return whether the row's Settings was clicked */
    private boolean paintActions(ClientView view, ScriptRow row, float right, float y, ClientActions actions) {
        ImGuiTheme.Metrics m = ui.m();
        List<Action> buttons = actionsFor(view, row);
        float x = right - buttons.size() * m.controlHeight() - Math.max(0, buttons.size() - 1) * m.u(1);
        boolean isConfigure = false;
        for (Action a : buttons) {
            ImGui.setCursorScreenPos(x, y);
            if (widgets.iconButton(a.kind().name() + "##" + view.id().value() + "/" + row.name(), a.icon(),
                    a.tint(), a.tooltip(), a.enabled())) {
                isConfigure |= perform(a.kind(), view.id(), row.name(), actions);
            }
            x += m.controlHeight() + m.u(1);
        }
        return isConfigure;
    }

    /** Does what a button asks; returns whether it asked for the inspector, which the page opens. */
    private static boolean perform(Kind kind, ClientKey client, String scriptName, ClientActions actions) {
        switch (kind) {
            case SETTINGS -> {
                return true;
            }
            case STOP -> actions.stopScript(client, scriptName);
            case RUN, RESTART -> actions.runScript(client, scriptName);
            case LOG -> actions.viewLog(client);
        }
        return false;
    }

    /** The buttons a row offers, left to right; none while its client is not connected. */
    private static List<Action> actionsFor(ClientView view, ScriptRow row) {
        if (!view.state().isConnected()) {
            return List.of();
        }
        String name = row.name();
        return switch (row.state()) {
            case ScriptState.Running _, ScriptState.Stalled _ -> List.of(settings(row),
                    new Action(Kind.STOP, Icons.STOP, IconTint.STOP, "Stop " + name, true));
            case ScriptState.Stopped _ -> List.of(settings(row),
                    new Action(Kind.RUN, Icons.PLAY, IconTint.RUN, "Run " + name, true));
            case ScriptState.Crashed _ -> List.of(
                    new Action(Kind.LOG, Icons.FILE_LINES, IconTint.PLAIN, "View crash log", true),
                    new Action(Kind.RESTART, Icons.REDO, IconTint.RUN, "Restart " + name, true));
            case ScriptState.CutOff _ ->
                    List.of(new Action(Kind.LOG, Icons.FILE_LINES, IconTint.PLAIN, "View thread dump", true));
            case ScriptState.Waiting _ -> List.of();
        };
    }

    private static Action settings(ScriptRow row) {
        ScriptInfo script = row.script();
        boolean hasSettings = script.settingsCount() > 0 || script.hasCustomUi();
        String tooltip = hasSettings ? "Settings for " + script.name() : script.name() + " has no settings";
        return new Action(Kind.SETTINGS, Icons.SLIDERS, IconTint.PLAIN, tooltip, hasSettings);
    }
}
