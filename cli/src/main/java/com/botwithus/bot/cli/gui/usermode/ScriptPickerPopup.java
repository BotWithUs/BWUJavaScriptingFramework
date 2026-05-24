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
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.ImVec2;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Locale;

/**
 * Modal popup for selecting a script to start on a given connection.
 *
 * Sized proportionally to the main viewport. Items are hand-drawn — a left
 * category stripe, icon, name, right-aligned author chip, and a dimmed
 * description line — so layout doesn't drift when the font changes.
 */
public class ScriptPickerPopup {

    private static final String POPUP_ID = "Start Script###scriptPicker";

    /** Popup width as a fraction of the main viewport width, clamped by the em bounds below. */
    private static final float POPUP_WIDTH_FRACTION = 0.45f;
    /** Popup height as a fraction of the main viewport height. */
    private static final float POPUP_HEIGHT_FRACTION = 0.70f;
    /** Minimum popup width in em (font-size multiples). */
    private static final float POPUP_MIN_W_EM = 26f;
    /** Maximum popup width in em. */
    private static final float POPUP_MAX_W_EM = 40f;
    /** Minimum popup height in em. */
    private static final float POPUP_MIN_H_EM = 22f;
    /** Maximum popup height in em. */
    private static final float POPUP_MAX_H_EM = 36f;

    private final ImString searchQuery = new ImString(128);
    private Connection targetConnection;
    private List<BotScript> availableScripts;
    private int selectedIndex = -1;
    private boolean shouldOpen;

    public void open(Connection connection, List<BotScript> scripts) {
        this.targetConnection = connection;
        this.availableScripts = scripts;
        this.selectedIndex = -1;
        this.searchQuery.set("");
        this.shouldOpen = true;
    }

    public void render(CliContext ctx) {
        if (shouldOpen) {
            ImGui.openPopup(POPUP_ID);
            shouldOpen = false;
        }

        configureNextWindow();

        // Override popup padding for a more spacious feel; revert before content
        // so child padding stays standard.
        float fontH = ImGui.getFontSize();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, fontH * 0.95f, fontH * 0.85f);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, fontH * 0.55f);

        int flags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove;
        boolean open = ImGui.beginPopupModal(POPUP_ID, flags);
        ImGui.popStyleVar(2);

        if (!open) {
            return;
        }

        renderHeader();
        renderSearchInput();
        ImGui.spacing();

        String filter = searchQuery.get().toLowerCase(Locale.ROOT).trim();
        renderScriptList(ctx, filter);
        renderFooter(ctx);

        ImGui.endPopup();
    }

    private void configureNextWindow() {
        var viewport = ImGui.getMainViewport();
        float vw = viewport.getSizeX();
        float vh = viewport.getSizeY();
        float fontH = ImGui.getFontSize();

        float popupW = clamp(vw * POPUP_WIDTH_FRACTION, fontH * POPUP_MIN_W_EM, fontH * POPUP_MAX_W_EM);
        float popupH = clamp(vh * POPUP_HEIGHT_FRACTION, fontH * POPUP_MIN_H_EM, fontH * POPUP_MAX_H_EM);

        ImGui.setNextWindowSize(popupW, popupH);
        ImGui.setNextWindowPos(
                viewport.getPosX() + (vw - popupW) * 0.5f,
                viewport.getPosY() + (vh - popupH) * 0.5f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── Header / search ────────────────────────────────────────────────

    private void renderHeader() {
        ImDrawList draw = ImGui.getWindowDrawList();
        float fontH = ImGui.getFontSize();

        // Accent bar + "Start Script on [connection name]" title
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float lineH = ImGui.getTextLineHeight();
        float barW = Math.max(2f, fontH * 0.18f);
        int accent = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        draw.addRectFilled(x, y, x + barW, y + lineH, accent, barW * 0.5f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + barW + ImGui.getStyle().getItemInnerSpacingX() * 1.5f);
        ImGui.text("Start Script");

        String target = displayName(targetConnection);
        if (!target.isEmpty()) {
            ImGui.sameLine(0, ImGui.getStyle().getItemSpacingX());
            GuiHelpers.textMuted("on " + target);
        }

        ImGui.dummy(0f, ImGui.getStyle().getItemSpacingY() * 0.5f);
    }

    private static String displayName(Connection c) {
        if (c == null) return "";
        String accountName = c.getAccountName();
        if (accountName != null && !accountName.isEmpty()) {
            return accountName;
        }
        return c.getName() != null ? c.getName() : "";
    }

    private void renderSearchInput() {
        ImGui.pushItemWidth(-1);
        ImGui.inputTextWithHint("##scriptSearch", Icons.SEARCH + "  Search scripts...", searchQuery);
        ImGui.popItemWidth();
    }

    // ── List ───────────────────────────────────────────────────────────

    private void renderScriptList(CliContext ctx, String filter) {
        ImGuiStyle style = ImGui.getStyle();
        float footerReserve = style.getItemSpacingY() * 2f + 1f  // separator + spacing
                            + style.getItemSpacingY()             // pre-button gap
                            + ImGui.getFrameHeight();              // footer button row
        float listHeight = ImGui.getContentRegionAvailY() - footerReserve;

        // Slightly tighter padding inside the list for denser rows
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding,
                ImGui.getFontSize() * 0.3f, ImGui.getFontSize() * 0.3f);
        ImGui.beginChild("##scriptList", -1, listHeight, false);
        ImGui.popStyleVar();

        if (availableScripts == null || availableScripts.isEmpty()) {
            renderCenteredMutedMessage("No scripts available");
        } else {
            int visibleCount = 0;
            for (int i = 0; i < availableScripts.size(); i++) {
                if (renderScriptItem(ctx, i, filter)) {
                    visibleCount++;
                }
            }
            if (visibleCount == 0 && !filter.isEmpty()) {
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
            String n = name.toLowerCase(Locale.ROOT);
            String d = desc.toLowerCase(Locale.ROOT);
            String a = author.toLowerCase(Locale.ROOT);
            if (!n.contains(filter) && !d.contains(filter) && !a.contains(filter)) {
                return false;
            }
        }

        boolean isSelected = (i == selectedIndex);
        CategoryStyle.Style cat = CategoryStyle.of(category);

        // Geometry — all derived from font / style
        float fontH = ImGui.getFontSize();
        float padX = fontH * 0.5f;
        float padY = fontH * 0.35f;
        float stripeW = Math.max(2f, fontH * 0.18f);
        float iconCellW = fontH * 1.5f;
        float rounding = fontH * 0.28f;
        boolean hasDesc = !desc.isEmpty();

        float availW = ImGui.getContentRegionAvailX();
        float rowH = padY * 2f + fontH + (hasDesc ? fontH * 1.0f + fontH * 0.15f : 0f);

        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();

        ImGui.invisibleButton("##script" + i, availW, rowH);
        boolean hovered = ImGui.isItemHovered();
        boolean clicked = ImGui.isItemClicked();
        boolean dblClicked = hovered && ImGui.isMouseDoubleClicked(0);

        if (clicked) {
            selectedIndex = i;
        }
        if (dblClicked) {
            selectedIndex = i;
            startSelected(ctx);
            ImGui.closeCurrentPopup();
        }

        ImDrawList draw = ImGui.getWindowDrawList();
        float hoverT = Motion.hover("pick:" + i, hovered && !isSelected);

        // Row background — selected gets a category-tinted wash; hover is more subtle
        if (isSelected) {
            int bgSel = ImGuiTheme.imCol32(cat.r(), cat.g(), cat.b(), 0.14f);
            draw.addRectFilled(x0, y0, x0 + availW, y0 + rowH, bgSel, rounding);
            int borderSel = ImGuiTheme.imCol32(cat.r(), cat.g(), cat.b(), 0.45f);
            draw.addRect(x0, y0, x0 + availW, y0 + rowH, borderSel, rounding);
        } else if (hoverT > 0.001f) {
            int bgHover = ImGuiTheme.imCol32(
                    ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B,
                    0.7f * hoverT);
            draw.addRectFilled(x0, y0, x0 + availW, y0 + rowH, bgHover, rounding);
        }

        // Left category stripe (slightly inset vertically so it floats nicely)
        float stripeInsetY = padY * 0.7f;
        float stripeAlpha = isSelected ? 0.95f : 0.55f + 0.35f * hoverT;
        int stripeCol = ImGuiTheme.imCol32(cat.r(), cat.g(), cat.b(), stripeAlpha);
        draw.addRectFilled(
                x0 + padX * 0.4f, y0 + stripeInsetY,
                x0 + padX * 0.4f + stripeW, y0 + rowH - stripeInsetY,
                stripeCol, stripeW * 0.5f, ImDrawFlags.RoundCornersAll);

        // Icon
        float contentX = x0 + padX * 0.4f + stripeW + padX;
        float topRowY = y0 + padY;
        int iconCol = ImGuiTheme.imCol32(cat.r(), cat.g(), cat.b(), 1f);
        draw.addText(contentX, topRowY, iconCol, cat.icon());

        // Name (always full opacity white text)
        float nameX = contentX + iconCellW;
        int nameCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 1f);

        // Right-aligned author chip — claim its width first so we can clip the name text
        float rightLimit = x0 + availW - padX;
        if (!author.isEmpty()) {
            String byLine = "by " + author;
            ImVec2 sz = new ImVec2();
            ImGui.calcTextSize(sz, byLine);
            int authorCol = ImGuiTheme.imCol32(
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f);
            float ax = rightLimit - sz.x;
            draw.addText(ax, topRowY, authorCol, byLine);
            rightLimit = ax - ImGui.getStyle().getItemSpacingX();
        }

        // Clip the name to the space available between icon and author chip
        draw.pushClipRect(nameX, topRowY, rightLimit, topRowY + fontH * 1.2f, true);
        draw.addText(nameX, topRowY, nameCol, name);
        draw.popClipRect();

        // Description on the second line, dimmed, clipped to row width
        if (hasDesc) {
            float descY = topRowY + fontH + fontH * 0.15f;
            int descCol = ImGuiTheme.imCol32(
                    ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.78f);
            draw.pushClipRect(nameX, descY, x0 + availW - padX, descY + fontH * 1.2f, true);
            draw.addText(nameX, descY, descCol, desc);
            draw.popClipRect();
        }

        return true;
    }

    private static void renderCenteredMutedMessage(String text) {
        ImGui.dummy(0f, ImGui.getFontSize());
        float w = ImGui.calcTextSize(text).x;
        ImGui.setCursorPosX((ImGui.getWindowWidth() - w) * 0.5f);
        GuiHelpers.textMuted(text);
    }

    // ── Footer ─────────────────────────────────────────────────────────

    private void renderFooter(CliContext ctx) {
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        float fontH = ImGui.getFontSize();
        float frameH = ImGui.getFrameHeight();
        float availW = ImGui.getContentRegionAvailX();
        float gap = ImGui.getStyle().getItemSpacingX();
        float buttonW = Math.max(fontH * 6.5f, availW * 0.18f);
        float totalW = buttonW * 2f + gap;

        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0f, availW - totalW));

        if (GuiHelpers.buttonSecondary("Cancel", buttonW, frameH)) {
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine(0, gap);

        boolean canStart = selectedIndex >= 0;
        if (!canStart) {
            ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.4f);
        }
        if (GuiHelpers.buttonPrimary(Icons.PLAY + "  Start", buttonW, frameH) && canStart) {
            startSelected(ctx);
            ImGui.closeCurrentPopup();
        }
        if (!canStart) {
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
