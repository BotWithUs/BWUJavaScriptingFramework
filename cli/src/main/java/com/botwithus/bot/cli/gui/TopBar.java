package com.botwithus.bot.cli.gui;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Top navigation bar with three mode tabs: Launcher, Normal, Advanced.
 * Features a sliding accent underline on the active tab and
 * a compact brand mark on the left.
 */
public class TopBar {

    // Tab definitions
    private static final String[] TAB_ICONS = {Icons.GAMEPAD, Icons.TH_LARGE, Icons.CODE};
    private static final String[] TAB_LABELS = {"Launcher", "Normal", "Advanced"};
    private static final AppMode[] TAB_MODES = {AppMode.LAUNCHER, AppMode.NORMAL, AppMode.ADVANCED};

    // Animated underline position (smoothly slides between tabs)
    private float underlineX = -1f;
    private float underlineW = 0f;

    /**
     * Render the top bar. Returns the new mode if a tab was clicked, or null if unchanged.
     */
    public AppMode render(AppMode currentMode, float dpiScale) {
        AppMode newMode = null;
        float barHeight = ImGui.getFrameHeight() + ImGui.getStyle().getWindowPaddingY() * 2
                + 4f * dpiScale; // extra space for underline

        // Bar background — slightly darker than main bg
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SIDEBAR_BG_R, ImGuiTheme.SIDEBAR_BG_G, ImGuiTheme.SIDEBAR_BG_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.25f);

        ImGui.beginChild("##topbar", 0, barHeight, true);
        ImGui.popStyleColor(2);

        ImDrawList draw = ImGui.getWindowDrawList();
        float windowWidth = ImGui.getWindowWidth();
        float windowX = ImGui.getWindowPosX();
        float windowY = ImGui.getWindowPosY();
        float contentCenterY = (barHeight - ImGui.getTextLineHeight()) / 2f - 2f * dpiScale;
        float btnCenterY = (barHeight - ImGui.getFrameHeight()) / 2f - 2f * dpiScale;

        // ── Left: Brand mark ──────────────────────────────────────────
        float padX = ImGui.getStyle().getWindowPaddingX();
        ImGui.setCursorPos(padX, contentCenterY);

        float logoX = ImGui.getCursorScreenPosX();
        float logoY = ImGui.getCursorScreenPosY();
        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        int accentDim = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);

        // Two-dot brand mark
        float dotSize = 3f * dpiScale;
        float textH = ImGui.getTextLineHeight();
        draw.addRectFilled(logoX, logoY, logoX + dotSize, logoY + textH, accentCol, 1.5f);
        draw.addRectFilled(logoX + dotSize + 2f * dpiScale, logoY,
                logoX + dotSize * 2 + 2f * dpiScale, logoY + textH * 0.5f, accentDim, 1.5f);

        ImGui.setCursorPosX(padX + dotSize * 2 + 8f * dpiScale);
        ImGui.setCursorPosY(contentCenterY);
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.85f);
        ImGui.text("BWU");
        ImGui.popStyleColor();

        // ── Center: Tab buttons ───────────────────────────────────────
        // Measure total tab strip width first
        float tabGap = 2f * dpiScale;
        float tabPadX = 14f * dpiScale;
        float[] tabWidths = new float[TAB_LABELS.length];
        float totalTabWidth = 0;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            String full = TAB_ICONS[i] + "  " + TAB_LABELS[i];
            tabWidths[i] = ImGui.calcTextSize(full).x + tabPadX * 2;
            totalTabWidth += tabWidths[i];
        }
        totalTabWidth += tabGap * (TAB_LABELS.length - 1);

        float tabStartX = (windowWidth - totalTabWidth) / 2f;
        float cursorX = tabStartX;

        // Track the active tab's screen-space position for the underline
        float activeTabScreenX = 0;
        float activeTabW = 0;

        for (int i = 0; i < TAB_LABELS.length; i++) {
            boolean isActive = (currentMode == TAB_MODES[i]);

            ImGui.setCursorPos(cursorX, btnCenterY);

            // Style: active tab gets a subtle filled background, inactive is transparent
            if (isActive) {
                ImGui.pushStyleColor(ImGuiCol.Button,
                        ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.10f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                        ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.16f);
                ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                        ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.22f);
                ImGui.pushStyleColor(ImGuiCol.Text,
                        ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
            } else {
                ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
                ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                        ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.04f);
                ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                        ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.07f);
                ImGui.pushStyleColor(ImGuiCol.Text,
                        ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.85f);
            }

            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4f * dpiScale);
            String btnLabel = TAB_ICONS[i] + "  " + TAB_LABELS[i] + "##tab" + i;
            if (ImGui.button(btnLabel, tabWidths[i], ImGui.getFrameHeight())) {
                newMode = TAB_MODES[i];
            }
            ImGui.popStyleVar();
            ImGui.popStyleColor(4);

            if (isActive) {
                activeTabScreenX = ImGui.getItemRectMinX();
                activeTabW = ImGui.getItemRectMaxX() - ImGui.getItemRectMinX();
            }

            cursorX += tabWidths[i] + tabGap;
        }

        // ── Sliding underline indicator ───────────────────────────────
        // Smooth interpolation toward the active tab
        float dt = ImGui.getIO().getDeltaTime();
        float lerpSpeed = 12f; // fast but smooth
        if (underlineX < 0) {
            // First frame — snap
            underlineX = activeTabScreenX;
            underlineW = activeTabW;
        } else {
            underlineX += (activeTabScreenX - underlineX) * Math.min(1f, lerpSpeed * dt);
            underlineW += (activeTabW - underlineW) * Math.min(1f, lerpSpeed * dt);
        }

        float underlineY = windowY + barHeight - 3f * dpiScale;
        float underlineH = 2.5f * dpiScale;

        // Subtle glow behind the bar
        int glowCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.12f);
        draw.addRectFilled(underlineX - 4f, underlineY - 1f,
                underlineX + underlineW + 4f, underlineY + underlineH + 1f, glowCol, 2f);

        // The sharp accent line
        draw.addRectFilled(underlineX, underlineY,
                underlineX + underlineW, underlineY + underlineH, accentCol, 1.5f);

        // ── Right: Keyboard shortcut hints ────────────────────────────
        String hint = "F12";
        float hintW = ImGui.calcTextSize(hint).x;
        ImGui.setCursorPos(windowWidth - hintW - padX, contentCenterY);
        ImGui.textColored(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.35f,
                hint);

        ImGui.endChild();

        return newMode;
    }
}
