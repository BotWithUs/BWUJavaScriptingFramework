package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImGui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Renders the Normal Mode dashboard — a responsive card grid
 * showing each connected client with script controls.
 * Account management is handled separately in the Launcher tab.
 */
public class UserModeRenderer {

    /** Minimum card width as a multiple of the current font size. ~352px at 16px font. */
    private static final float MIN_CARD_WIDTH_EM = 22f;

    private final ClientCard clientCard = new ClientCard();
    private final ScriptPickerPopup scriptPicker = new ScriptPickerPopup();
    private Consumer<ScriptRunner> configPanelOpener;

    /**
     * Set the callback that opens the script config panel (floating window).
     * This should be wired to the same opener used in Advanced mode.
     */
    public void setConfigPanelOpener(Consumer<ScriptRunner> opener) {
        this.configPanelOpener = opener;
    }

    /**
     * Render the Normal Mode dashboard.
     */
    public void render(CliContext ctx) {
        float availHeight = ImGui.getContentRegionAvailY();
        ImGui.beginChild("##usermode", 0, availHeight, false);

        Collection<Connection> connections = ctx.getConnections();

        if (connections.isEmpty()) {
            renderEmptyState();
        } else {
            renderCardGrid(ctx, new ArrayList<>(connections));
        }

        // Always render the script picker popup (it's a no-op when closed)
        scriptPicker.render(ctx);

        ImGui.endChild();
    }

    private void renderCardGrid(CliContext ctx, List<Connection> connections) {
        float availWidth = ImGui.getContentRegionAvailX();
        float fontH = ImGui.getFontSize();
        float minCardWidth = fontH * MIN_CARD_WIDTH_EM;
        float cardSpacing = ImGui.getStyle().getItemSpacingX() * 1.5f;

        int columns = Math.max(1, (int) ((availWidth + cardSpacing) / (minCardWidth + cardSpacing)));
        float cardWidth = (availWidth - (columns - 1) * cardSpacing) / columns;

        ImGui.spacing();

        int col = 0;
        for (int i = 0; i < connections.size(); i++) {
            Connection connection = connections.get(i);

            if (col > 0) {
                ImGui.sameLine(0, cardSpacing);
            }

            ClientCard.CardAction action = clientCard.render(connection, cardWidth, i);

            if (action != null) {
                handleAction(ctx, action);
            }

            col++;
            if (col >= columns) {
                col = 0;
                ImGui.dummy(0f, cardSpacing * 0.5f);
            }
        }
    }

    private void handleAction(CliContext ctx, ClientCard.CardAction action) {
        switch (action.type()) {
            case START_SCRIPT -> {
                // Load available scripts and open the picker
                List<BotScript> scripts = ctx.loadScripts();
                List<BotScript> blueprints = ctx.loadBlueprints();
                List<BotScript> all = new ArrayList<>(scripts);
                all.addAll(blueprints);
                scriptPicker.open(action.connection(), all);
            }
            case STOP -> {
                if (action.runner() != null) {
                    action.runner().stop();
                }
            }
            case CONFIGURE -> {
                if (action.runner() != null && configPanelOpener != null) {
                    configPanelOpener.accept(action.runner());
                }
            }
            case RECONNECT -> {
                // Close the dead connection and reconnect with the same pipe name
                String pipeName = action.connection().getName();
                ctx.disconnect(pipeName, true);
                ctx.connect(pipeName);
            }
        }
    }

    private void renderEmptyState() {
        float windowWidth = ImGui.getWindowWidth();
        float windowHeight = ImGui.getWindowHeight();
        float lineH = ImGui.getTextLineHeight();

        String icon = Icons.GAMEPAD;
        String title = "No game clients connected";
        String subtitle = "Launch your game client and it will";
        String subtitle2 = "appear here automatically.";
        String hint = "Press F12 to cycle modes \u2022 Use Launcher tab to add accounts";

        // Center vertically \u2014 estimate total stack height
        float totalHeight = lineH * 7f;
        ImGui.setCursorPosY((windowHeight - totalHeight) * 0.5f);

        centerText(icon, windowWidth, () ->
                ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.3f, icon));

        ImGui.dummy(0f, lineH * 0.4f);

        centerText(title, windowWidth, () -> ImGui.text(title));
        centerText(subtitle, windowWidth, () -> GuiHelpers.textSecondary(subtitle));
        centerText(subtitle2, windowWidth, () -> GuiHelpers.textSecondary(subtitle2));

        ImGui.dummy(0f, lineH * 0.6f);

        centerText(hint, windowWidth, () -> GuiHelpers.textMuted(hint));
    }

    private static void centerText(String text, float windowWidth, Runnable draw) {
        float width = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX((windowWidth - width) * 0.5f);
        draw.run();
    }
}
