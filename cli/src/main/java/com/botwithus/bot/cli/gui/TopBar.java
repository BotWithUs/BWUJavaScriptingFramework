package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Top navigation bar with three mode tabs: Launcher, Normal, Advanced.
 *
 * Layout (left → right):
 *   [brand mark] [BWU text]           [ tab  tab  tab ]           [live session pill]
 *                                      │──underline──│
 *
 * All sizes derive from font size / frame height so the bar scales cleanly
 * across DPI settings. The sliding underline is lerped per-frame.
 */
public class TopBar {

    public TopBar() {}

    private static final String[] TAB_ICONS = {Icons.GAMEPAD, Icons.TH_LARGE, Icons.CODE};
    private static final String[] TAB_LABELS = {"Launcher", "Normal", "Advanced"};
    private static final AppMode[] TAB_MODES = {AppMode.LAUNCHER, AppMode.NORMAL, AppMode.ADVANCED};

    private static final float UNDERLINE_LERP_SPEED = 14f;

    // Animated underline position (smoothly slides between tabs)
    private float underlineX = -1f;
    private float underlineW = 0f;

    /** Result of rendering the tab row -- which tab was clicked, and the geometry of the active tab. */
    private record TabResult(AppMode clicked, float activeTabScreenX, float activeTabWidth) {}

    /** Session counts derived from the {@link CliContext} for the right-hand pill. */
    private record SessionCounts(int connections, int runningScripts, boolean connected) {}

    /**
     * Render the top bar. Returns the new mode if a tab was clicked, or null if unchanged.
     * {@code ctx} may be null during the first paint if the app hasn't wired it yet.
     */
    public AppMode render(AppMode currentMode, float dpiScale, CliContext ctx) {
        float fontH = ImGui.getFontSize();
        float frameH = ImGui.getFrameHeight();
        float padY = ImGui.getStyle().getWindowPaddingY();
        // Tight, premium vertical footprint
        float barHeight = frameH + padY * 2f + fontH * 0.25f;

        beginTopBarChild(barHeight);

        float contentCenterY = (barHeight - ImGui.getTextLineHeight()) * 0.5f - fontH * 0.12f;
        float btnCenterY = (barHeight - frameH) * 0.5f - fontH * 0.12f;

        renderBrand(contentCenterY, fontH);
        TabResult tabs = renderTabs(currentMode, btnCenterY, fontH, frameH);
        renderUnderline(tabs, barHeight, fontH);
        renderSessionPill(ctx, barHeight, fontH);

        ImGui.endChild();
        return tabs.clicked();
    }

    private static void beginTopBarChild(float barHeight) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SIDEBAR_BG_R, ImGuiTheme.SIDEBAR_BG_G, ImGuiTheme.SIDEBAR_BG_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.25f);
        ImGui.beginChild("##topbar", 0, barHeight, true);
        ImGui.popStyleColor(2);
    }

    private static void renderBrand(float contentCenterY, float fontH) {
        float padX = ImGui.getStyle().getWindowPaddingX();
        ImGui.setCursorPos(padX, contentCenterY);

        ImDrawList draw = ImGui.getWindowDrawList();
        float logoX = ImGui.getCursorScreenPosX();
        float logoY = ImGui.getCursorScreenPosY();
        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        int accentDim = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.25f);

        // Two-dot brand mark — one full-height, one half-height
        float dotSize = Math.max(2f, fontH * 0.22f);
        float textH = ImGui.getTextLineHeight();
        float dotGap = fontH * 0.15f;
        float dotRound = dotSize * 0.55f;
        draw.addRectFilled(logoX, logoY, logoX + dotSize, logoY + textH, accentCol, dotRound);
        draw.addRectFilled(logoX + dotSize + dotGap, logoY,
                logoX + dotSize * 2f + dotGap, logoY + textH * 0.5f, accentDim, dotRound);

        ImGui.setCursorPosX(padX + dotSize * 2f + dotGap + fontH * 0.55f);
        ImGui.setCursorPosY(contentCenterY);
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.88f);
        ImGui.text("BWU");
        ImGui.popStyleColor();
    }

    private TabResult renderTabs(AppMode currentMode, float btnCenterY, float fontH, float frameH) {
        AppMode clicked = null;
        float tabGap = fontH * 0.15f;
        float tabPadX = fontH;
        float[] tabWidths = new float[TAB_LABELS.length];
        float totalTabWidth = 0f;
        ImVec2 tmp = new ImVec2();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            String full = TAB_ICONS[i] + "  " + TAB_LABELS[i];
            ImGui.calcTextSize(tmp, full);
            tabWidths[i] = tmp.x + tabPadX * 2f;
            totalTabWidth += tabWidths[i];
        }
        totalTabWidth += tabGap * (TAB_LABELS.length - 1);

        float cursorX = (ImGui.getWindowWidth() - totalTabWidth) * 0.5f;
        float activeTabScreenX = 0f;
        float activeTabW = 0f;

        for (int i = 0; i < TAB_LABELS.length; i++) {
            boolean isActive = (currentMode == TAB_MODES[i]);
            ImGui.setCursorPos(cursorX, btnCenterY);
            pushTabColors(isActive);

            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, fontH * 0.3f);
            String btnLabel = TAB_ICONS[i] + "  " + TAB_LABELS[i] + "##tab" + i;
            if (ImGui.button(btnLabel, tabWidths[i], frameH)) {
                clicked = TAB_MODES[i];
            }
            ImGui.popStyleVar();
            ImGui.popStyleColor(4);

            if (isActive) {
                activeTabScreenX = ImGui.getItemRectMinX();
                activeTabW = ImGui.getItemRectMaxX() - ImGui.getItemRectMinX();
            }

            cursorX += tabWidths[i] + tabGap;
        }
        return new TabResult(clicked, activeTabScreenX, activeTabW);
    }

    private static void pushTabColors(boolean isActive) {
        if (isActive) {
            ImGui.pushStyleColor(ImGuiCol.Button,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.10f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.18f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.24f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.05f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.08f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 0.85f);
        }
    }

    private void renderUnderline(TabResult tabs, float barHeight, float fontH) {
        float dt = ImGui.getIO().getDeltaTime();
        if (underlineX < 0f) {
            underlineX = tabs.activeTabScreenX();
            underlineW = tabs.activeTabWidth();
        } else {
            float k = Math.min(1f, UNDERLINE_LERP_SPEED * dt);
            underlineX += (tabs.activeTabScreenX() - underlineX) * k;
            underlineW += (tabs.activeTabWidth() - underlineW) * k;
        }

        float underlineH = Math.max(2f, fontH * 0.16f);
        float underlineY = ImGui.getWindowPosY() + barHeight - underlineH - 2f;

        ImDrawList draw = ImGui.getWindowDrawList();
        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);

        // Soft glow behind the sharp line
        int glowCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.14f);
        float glowPad = fontH * 0.3f;
        draw.addRectFilled(underlineX - glowPad, underlineY - 1f,
                underlineX + underlineW + glowPad, underlineY + underlineH + 1f,
                glowCol, underlineH);

        // Sharp accent line
        draw.addRectFilled(underlineX, underlineY,
                underlineX + underlineW, underlineY + underlineH, accentCol, underlineH * 0.5f);
    }

    private static SessionCounts collectSessionCounts(CliContext ctx) {
        if (ctx == null) {
            return new SessionCounts(0, 0, false);
        }
        int connCount = ctx.getConnections().size();
        boolean connected = ctx.hasActiveConnection();
        int running = 0;
        for (Connection conn : ctx.getConnections()) {
            for (ScriptRunner runner : conn.getRuntime().getRunners()) {
                if (runner.isRunning()) {
                    running++;
                }
            }
        }
        return new SessionCounts(connCount, running, connected);
    }

    private static String formatPillText(SessionCounts counts) {
        if (counts.connections() == 0) {
            return "offline";
        }
        return counts.connections() + (counts.connections() == 1 ? " conn" : " conns")
                + "  ·  " + counts.runningScripts() + " running";
    }

    private static void renderSessionPill(CliContext ctx, float barHeight, float fontH) {
        SessionCounts counts = collectSessionCounts(ctx);
        boolean pillActive = counts.connected();
        String pillText = formatPillText(counts);

        ImVec2 tmp = new ImVec2();
        ImGui.calcTextSize(tmp, pillText);
        float pillTextW = tmp.x;
        float dotR = Math.max(2f, fontH * 0.22f);
        float pillPadX = fontH * 0.6f;
        float pillPadY = fontH * 0.22f;
        float pillGap = fontH * 0.45f;
        float pillW = dotR * 2f + pillGap + pillTextW + pillPadX * 2f;
        float pillH = tmp.y + pillPadY * 2f;

        String kbd = "F12";
        ImGui.calcTextSize(tmp, kbd);
        float kbdW = tmp.x + fontH * 0.6f;

        float rightEdge = ImGui.getWindowWidth() - ImGui.getStyle().getWindowPaddingX();
        float pillX = rightEdge - pillW - fontH * 0.5f - kbdW;
        float pillY = (barHeight - pillH) * 0.5f;
        ImGui.setCursorPos(pillX, pillY);
        drawSessionPill(pillActive, pillW, pillH, dotR, pillPadX, pillPadY, pillGap, pillText);

        ImGui.setCursorPos(rightEdge - kbdW, (barHeight - (ImGui.getTextLineHeight() + fontH * 0.16f)) * 0.5f);
        GuiHelpers.kbdHint(kbd);
    }

    private static void drawSessionPill(boolean pillActive, float pillW, float pillH,
                                        float dotR, float pillPadX, float pillPadY,
                                        float pillGap, String pillText) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float pillSX = ImGui.getCursorScreenPosX();
        float pillSY = ImGui.getCursorScreenPosY();

        float r = pillActive ? ImGuiTheme.ACCENT_R : ImGuiTheme.DIM_TEXT_R;
        float g = pillActive ? ImGuiTheme.ACCENT_G : ImGuiTheme.DIM_TEXT_G;
        float b = pillActive ? ImGuiTheme.ACCENT_B : ImGuiTheme.DIM_TEXT_B;
        int bg = ImGuiTheme.imCol32(r, g, b, pillActive ? 0.10f : 0.06f);
        int border = ImGuiTheme.imCol32(r, g, b, pillActive ? 0.35f : 0.2f);
        int textCol = ImGuiTheme.imCol32(
                ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, pillActive ? 0.92f : 0.55f);
        float pillRound = pillH * 0.5f;
        draw.addRectFilled(pillSX, pillSY, pillSX + pillW, pillSY + pillH, bg, pillRound);
        draw.addRect(pillSX, pillSY, pillSX + pillW, pillSY + pillH, border, pillRound);

        // Live/Idle dot (pulses when active)
        float dotCX = pillSX + pillPadX + dotR;
        float dotCY = pillSY + pillH * 0.5f;
        int dotCol = ImGuiTheme.imCol32(r, g, b, 1f);
        if (pillActive) {
            float pulse = Motion.pulse(0.9);
            int glow = ImGuiTheme.imCol32(r, g, b, 0.10f + pulse * 0.25f);
            draw.addCircleFilled(dotCX, dotCY, dotR * (1.6f + pulse * 0.6f), glow);
        }
        draw.addCircleFilled(dotCX, dotCY, dotR, dotCol);

        // Pill text
        draw.addText(pillSX + pillPadX + dotR * 2f + pillGap,
                pillSY + pillPadY, textCol, pillText);
    }
}
