package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.launcher.CloseRequestPrompt;
import com.botwithus.bot.core.launcher.CloseDecision;
import com.botwithus.bot.core.launcher.CloseRequest;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.Objects;
import java.util.Optional;

/**
 * The modal that asks the user whether to close this host so a data update
 * can apply (launcher ADR 0007, section 6.3). Drawn on the render thread from
 * {@link CloseRequestPrompt}, which received the request on the service
 * connection's thread.
 *
 * <p>"Close now" is the hero: it is what the launcher is waiting for, and it
 * is safe, because clients keep running without the host. "Later" and "Not for
 * this update" recede. Esc means Later. Nothing closes the host but "Close now".</p>
 */
final class CloseRequestModal {

    private static final String POPUP_ID = "##launcher-close-request";
    private static final String TITLE = "A data update is ready";
    private static final String QUESTION = "BotWithUs has a data update ready. Close this host now?";
    private static final String NOTE = "Your game clients keep running. Open the host again once the "
            + "update has applied.";
    private static final float WIDTH_EM = 30f;
    private static final float HEAD_EM = 3.733f;
    private static final float ICON_TILE_EM = 2f;
    private static final float LINE_EM = 1.5f;
    private static final int STYLE_VARS = 3;
    private static final int STYLE_COLORS = 3;
    /** Frames the request is on screen before a development auto-answer presses its button. */
    private static final int AUTO_ANSWER_AFTER_FRAMES = 2;

    private final Controls ui;
    private final CloseRequestPrompt prompt;
    private CloseRequest showing;
    private int framesShown;

    CloseRequestModal(Controls ui, CloseRequestPrompt prompt) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    /** Opens the modal when a request is waiting, and draws it while it is up. Render thread only. */
    void render() {
        Optional<CloseRequest> request = prompt.current();
        if (request.isEmpty()) {
            showing = null;
            return;
        }
        if (request.get() != showing) {
            showing = request.get();
            framesShown = 0;
            ImGui.openPopup(POPUP_ID);
        }
        place();
        pushStyle();
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings;
        boolean isOpen = ImGui.beginPopupModal(POPUP_ID, flags);
        popStyle();
        if (!isOpen) {
            return;
        }
        prompt.markShown(showing);
        framesShown++;
        Optional<CloseDecision> decision = content(showing);
        if (decision.isEmpty() && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            decision = Optional.of(CloseDecision.LATER);
        }
        if (decision.isEmpty() && framesShown >= AUTO_ANSWER_AFTER_FRAMES) {
            decision = prompt.autoAnswer();
        }
        decision.ifPresent(d -> {
            prompt.answer(showing, d);
            ImGui.closeCurrentPopup();
        });
        ImGui.endPopup();
    }

    private ImGuiTheme.Metrics m() {
        return ui.m();
    }

    private float fs() {
        return ImGui.getFontSize();
    }

    private void place() {
        var vp = ImGui.getMainViewport();
        float margin = m().u(5) * 2f;
        float width = Math.min(fs() * WIDTH_EM, vp.getSizeX() - margin);
        ImGui.setNextWindowSize(width, 0f);
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() * 0.5f, vp.getPosY() + vp.getSizeY() * 0.5f,
                0, 0.5f, 0.5f);
    }

    private void pushStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m().u(5), m().u(4));
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m().radiusXl());
        ImGui.pushStyleVar(ImGuiStyleVar.PopupBorderSize, m().hairline());
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ModalWindowDimBg, ImGuiTheme.COL_SCRIM);
    }

    private static void popStyle() {
        ImGui.popStyleColor(STYLE_COLORS);
        ImGui.popStyleVar(STYLE_VARS);
    }

    /** Draws the body and the buttons; returns the decision if a button was pressed. */
    private Optional<CloseDecision> content(CloseRequest request) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        float tile = fs() * ICON_TILE_EM;
        ui.iconTile(draw, x, y, tile, Icons.DOWNLOAD, ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT);
        float textX = x + tile + m().u(3);
        float headH = fs() * HEAD_EM * 0.5f;
        ui.textCentredY(draw, ui.fonts().bodyMedium(), textX, y, tile, ImGuiTheme.COL_FG, TITLE);
        float cy = y + Math.max(tile, headH) + m().u(3);
        float line = fs() * LINE_EM;
        for (String text : ui.wrap(ui.fonts().body(), QUESTION, width)) {
            ui.text(draw, ui.fonts().body(), x, cy, ImGuiTheme.COL_FG, text);
            cy += line;
        }
        for (String text : ui.wrap(ui.fonts().small(), blockingLine(request) + " " + NOTE, width)) {
            ui.text(draw, ui.fonts().small(), x, cy, ImGuiTheme.COL_FG2, text);
            cy += line;
        }
        ImGui.setCursorScreenPos(x, cy + m().u(3));
        return buttons(width);
    }

    private Optional<CloseDecision> buttons(float width) {
        String later = "Later";
        String decline = "Not for this update";
        String close = "Close now";
        float gap = m().u(2);
        float total = ui.buttonWidth(null, decline, Controls.Tone.GHOST)
                + ui.buttonWidth(null, later, Controls.Tone.GHOST)
                + ui.buttonWidth(Icons.DOWNLOAD, close, Controls.Tone.PRIMARY) + gap * 2f;
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX() + Math.max(0f, width - total),
                ImGui.getCursorScreenPosY());
        Optional<CloseDecision> pressed = Optional.empty();
        if (ui.button("##close-decline", null, decline, Controls.Tone.GHOST, true)) {
            pressed = Optional.of(CloseDecision.DECLINED);
        }
        ImGui.sameLine(0f, gap);
        if (ui.button("##close-later", null, later, Controls.Tone.GHOST, true)) {
            pressed = Optional.of(CloseDecision.LATER);
        }
        ImGui.sameLine(0f, gap);
        if (ui.button("##close-now", Icons.DOWNLOAD, close, Controls.Tone.PRIMARY, true)) {
            pressed = Optional.of(CloseDecision.CLOSING);
        }
        return pressed;
    }

    private static String blockingLine(CloseRequest request) {
        long hosts = request.hostsBlocking();
        return hosts == 1 ? "This host is the only one it is waiting for."
                : "It is waiting for " + hosts + " open hosts.";
    }
}
