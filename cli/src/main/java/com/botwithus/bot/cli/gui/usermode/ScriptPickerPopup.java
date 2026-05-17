package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Locale;

/**
 * Modal popup for selecting a script to start on a given connection.
 */
public class ScriptPickerPopup {

    private static final String POPUP_ID = "Start Script###scriptPicker";

    private final ImString searchQuery = new ImString(128);
    private Connection targetConnection;
    private List<BotScript> availableScripts;
    private int selectedIndex = -1;
    private boolean shouldOpen;

    /**
     * Queue the popup to open for the given connection on the next frame.
     */
    public void open(Connection connection, List<BotScript> scripts) {
        this.targetConnection = connection;
        this.availableScripts = scripts;
        this.selectedIndex = -1;
        this.searchQuery.set("");
        this.shouldOpen = true;
    }

    /**
     * Render the popup. Call every frame.
     */
    public void render(CliContext ctx) {
        if (shouldOpen) {
            ImGui.openPopup(POPUP_ID);
            shouldOpen = false;
        }

        ImGui.setNextWindowSize(400, 420);
        int flags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove;
        if (!ImGui.beginPopupModal(POPUP_ID, flags)) {
            return;
        }

        ImGui.pushItemWidth(-1);
        ImGui.inputTextWithHint("##scriptSearch", Icons.SEARCH + "  Search scripts...", searchQuery);
        ImGui.popItemWidth();

        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        String filter = searchQuery.get().toLowerCase(Locale.ROOT).trim();
        renderScriptList(ctx, filter);
        renderFooterButtons(ctx);

        ImGui.endPopup();
    }

    private void renderScriptList(CliContext ctx, String filter) {
        float listHeight = ImGui.getContentRegionAvailY() - ImGui.getFrameHeightWithSpacing() - 10f;
        ImGui.beginChild("##scriptList", -1, listHeight, false);

        if (availableScripts == null || availableScripts.isEmpty()) {
            renderCenteredMutedMessage("No scripts available");
        } else {
            int visibleIndex = 0;
            for (int i = 0; i < availableScripts.size(); i++) {
                if (renderScriptItem(ctx, i, filter)) {
                    visibleIndex++;
                }
            }
            if (visibleIndex == 0 && !filter.isEmpty()) {
                renderCenteredMutedMessage("No scripts match your search");
            }
        }
        ImGui.endChild();
    }

    /** Returns true if the item was rendered (i.e. matched the filter). */
    private boolean renderScriptItem(CliContext ctx, int i, String filter) {
        BotScript script = availableScripts.get(i);
        ScriptManifest manifest = script.getClass().getAnnotation(ScriptManifest.class);
        String name = manifest != null ? manifest.name() : script.getClass().getSimpleName();
        String desc = manifest != null ? manifest.description() : "";
        String author = manifest != null ? manifest.author() : "";
        ScriptCategory category = manifest != null ? manifest.category() : ScriptCategory.UNCATEGORIZED;

        if (!filter.isEmpty()) {
            boolean matches = name.toLowerCase(Locale.ROOT).contains(filter)
                    || desc.toLowerCase(Locale.ROOT).contains(filter)
                    || author.toLowerCase(Locale.ROOT).contains(filter);
            if (!matches) {
                return false;
            }
        }

        boolean isSelected = (i == selectedIndex);
        CategoryStyle.Style style = CategoryStyle.of(category);

        if (isSelected) {
            pushSelectedItemColors();
        }
        if (ImGui.selectable("##script" + i, isSelected, 0, 0, ImGui.getFrameHeightWithSpacing() + 8f)) {
            selectedIndex = i;
        }
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
            selectedIndex = i;
            startSelected(ctx);
            ImGui.closeCurrentPopup();
        }
        if (isSelected) {
            ImGui.popStyleColor(3);
        }

        // Overlay content on the selectable
        ImGui.sameLine(8);
        ImGui.textColored(style.r(), style.g(), style.b(), 1f, style.icon());
        ImGui.sameLine(0, 8);
        ImGui.text(name);
        if (!author.isEmpty()) {
            ImGui.sameLine(0, 12);
            GuiHelpers.textMuted("by " + author);
        }

        if (!desc.isEmpty()) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + 28f);
            GuiHelpers.textSecondary(desc);
        }
        return true;
    }

    private static void pushSelectedItemColors() {
        ImGui.pushStyleColor(ImGuiCol.Header,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.15f);
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.22f);
        ImGui.pushStyleColor(ImGuiCol.HeaderActive,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.30f);
    }

    private static void renderCenteredMutedMessage(String text) {
        ImGui.spacing();
        ImGui.spacing();
        float w = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX((ImGui.getWindowWidth() - w) / 2f);
        GuiHelpers.textMuted(text);
    }

    private void renderFooterButtons(CliContext ctx) {
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        float buttonWidth = 100f;
        float totalWidth = buttonWidth * 2 + 8f;
        ImGui.setCursorPosX((ImGui.getWindowWidth() - totalWidth) / 2f);

        if (GuiHelpers.buttonSecondary("Cancel")) {
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine(0, 8);

        if (selectedIndex >= 0) {
            if (GuiHelpers.buttonPrimary(Icons.PLAY + "  Start")) {
                startSelected(ctx);
                ImGui.closeCurrentPopup();
            }
        } else {
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.Alpha, 0.4f);
            GuiHelpers.buttonPrimary(Icons.PLAY + "  Start");
            ImGui.popStyleVar();
        }
    }

    private void startSelected(CliContext ctx) {
        if (selectedIndex < 0 || availableScripts == null || selectedIndex >= availableScripts.size()) {
            return;
        }
        if (targetConnection == null) {
            return;
        }

        BotScript script = availableScripts.get(selectedIndex);
        targetConnection.getRuntime().startScript(script);
    }
}
