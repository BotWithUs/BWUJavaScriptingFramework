package com.botwithus.bot.cli.gui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

/**
 * Color constants and imgui style setup for the BotWithUs dark theme.
 * All colors in 0.0-1.0 float range.
 */
public final class ImGuiTheme {

    // Background layers (deep navy, layered for depth)
    public static final float BG_R = 0x0d / 255f, BG_G = 0x0f / 255f, BG_B = 0x14 / 255f;
    public static final float SURFACE_R = 0x14 / 255f, SURFACE_G = 0x17 / 255f, SURFACE_B = 0x1f / 255f;
    public static final float INPUT_BG_R = 0x1a / 255f, INPUT_BG_G = 0x1e / 255f, INPUT_BG_B = 0x28 / 255f;
    public static final float ELEVATED_R = 0x1f / 255f, ELEVATED_G = 0x23 / 255f, ELEVATED_B = 0x2e / 255f;

    // Text hierarchy
    public static final float TEXT_R = 0xec / 255f, TEXT_G = 0xed / 255f, TEXT_B = 0xf0 / 255f;
    public static final float TEXT_SEC_R = 0x9c / 255f, TEXT_SEC_G = 0x9e / 255f, TEXT_SEC_B = 0xa8 / 255f;
    public static final float DIM_TEXT_R = 0x5c / 255f, DIM_TEXT_G = 0x5f / 255f, DIM_TEXT_B = 0x6b / 255f;

    // Accent — refined emerald (#4ade80)
    public static final float ACCENT_R = 0x4a / 255f, ACCENT_G = 0xde / 255f, ACCENT_B = 0x80 / 255f;

    // Secondary accent — soft blue (#60a5fa)
    public static final float BLUE_ACCENT_R = 0x60 / 255f, BLUE_ACCENT_G = 0xa5 / 255f, BLUE_ACCENT_B = 0xfa / 255f;

    // Semantic colors
    public static final float RED_R = 0xf8 / 255f, RED_G = 0x71 / 255f, RED_B = 0x71 / 255f;
    public static final float GREEN_R = 0x4a / 255f, GREEN_G = 0xde / 255f, GREEN_B = 0x80 / 255f;
    public static final float YELLOW_R = 0xfb / 255f, YELLOW_G = 0xbd / 255f, YELLOW_B = 0x23 / 255f;
    public static final float BLUE_R = 0x60 / 255f, BLUE_G = 0xa5 / 255f, BLUE_B = 0xfa / 255f;
    public static final float MAGENTA_R = 0xc0 / 255f, MAGENTA_G = 0x84 / 255f, MAGENTA_B = 0xfc / 255f;
    public static final float CYAN_R = 0x67 / 255f, CYAN_G = 0xe8 / 255f, CYAN_B = 0xf9 / 255f;
    public static final float ORANGE_R = 0xfb / 255f, ORANGE_G = 0x92 / 255f, ORANGE_B = 0x3c / 255f;

    // Sidebar
    public static final float SIDEBAR_BG_R = 0x10 / 255f, SIDEBAR_BG_G = 0x13 / 255f, SIDEBAR_BG_B = 0x1a / 255f;

    // Border
    public static final float BORDER_R = 0x2a / 255f, BORDER_G = 0x2e / 255f, BORDER_B = 0x3a / 255f;

    private ImGuiTheme() {}

    /**
     * Map SGR color code (30-37) to float[]{r, g, b}.
     */
    public static float[] ansiColorFloat(int code) {
        return switch (code) {
            case 30 -> new float[]{BG_R, BG_G, BG_B};           // black -> background
            case 31 -> new float[]{RED_R, RED_G, RED_B};
            case 32 -> new float[]{GREEN_R, GREEN_G, GREEN_B};
            case 33 -> new float[]{YELLOW_R, YELLOW_G, YELLOW_B};
            case 34 -> new float[]{BLUE_R, BLUE_G, BLUE_B};
            case 35 -> new float[]{MAGENTA_R, MAGENTA_G, MAGENTA_B};
            case 36 -> new float[]{CYAN_R, CYAN_G, CYAN_B};
            case 37 -> new float[]{TEXT_R, TEXT_G, TEXT_B};       // white -> text
            default -> new float[]{TEXT_R, TEXT_G, TEXT_B};
        };
    }

    /**
     * Convert RGBA floats (0-1) to packed ImGui color integer (IM_COL32 format).
     */
    public static int imCol32(float r, float g, float b, float a) {
        return ((int)(a * 255f) << 24) | ((int)(b * 255f) << 16) | ((int)(g * 255f) << 8) | (int)(r * 255f);
    }

    /**
     * Apply the dark theme to the current imgui context with DPI scale factor of 1.0.
     */
    public static void apply() {
        apply(1.0f);
    }

    /**
     * Apply the dark theme to the current imgui context, scaling sizes by the given DPI factor.
     */
    public static void apply(float scale) {
        ImGuiStyle style = ImGui.getStyle();

        // Refined geometry — softer rounding, generous spacing
        style.setWindowRounding(0f);
        style.setChildRounding(6f);
        style.setFrameRounding(6f);
        style.setScrollbarRounding(8f);
        style.setGrabRounding(4f);
        style.setTabRounding(6f);
        style.setPopupRounding(6f);

        style.setWindowPadding(12f, 10f);
        style.setFramePadding(8f, 5f);
        style.setItemSpacing(8f, 6f);
        style.setItemInnerSpacing(6f, 4f);
        style.setScrollbarSize(10f);
        style.setIndentSpacing(16f);

        // Window border
        style.setWindowBorderSize(0f);
        style.setChildBorderSize(1f);
        style.setFrameBorderSize(0f);
        style.setPopupBorderSize(1f);
        style.setTabBorderSize(0f);

        // Let ImGui scale all sizes uniformly for DPI
        style.scaleAllSizes(scale);

        // -- Window & child backgrounds --
        style.setColor(ImGuiCol.WindowBg, BG_R, BG_G, BG_B, 1f);
        style.setColor(ImGuiCol.ChildBg, BG_R, BG_G, BG_B, 0f);
        style.setColor(ImGuiCol.PopupBg, SURFACE_R, SURFACE_G, SURFACE_B, 0.97f);

        // -- Borders --
        style.setColor(ImGuiCol.Border, BORDER_R, BORDER_G, BORDER_B, 0.6f);
        style.setColor(ImGuiCol.BorderShadow, 0f, 0f, 0f, 0f);

        // -- Input frames --
        style.setColor(ImGuiCol.FrameBg, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 1f);
        style.setColor(ImGuiCol.FrameBgHovered, ELEVATED_R, ELEVATED_G, ELEVATED_B, 1f);
        style.setColor(ImGuiCol.FrameBgActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.18f);

        // -- Title bar --
        style.setColor(ImGuiCol.TitleBg, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TitleBgActive, ELEVATED_R, ELEVATED_G, ELEVATED_B, 1f);
        style.setColor(ImGuiCol.TitleBgCollapsed, SURFACE_R, SURFACE_G, SURFACE_B, 0.6f);

        // -- Text --
        style.setColor(ImGuiCol.Text, TEXT_R, TEXT_G, TEXT_B, 1f);
        style.setColor(ImGuiCol.TextDisabled, DIM_TEXT_R, DIM_TEXT_G, DIM_TEXT_B, 1f);

        // -- Buttons (accent green with subtle opacity) --
        style.setColor(ImGuiCol.Button, ACCENT_R, ACCENT_G, ACCENT_B, 0.18f);
        style.setColor(ImGuiCol.ButtonHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.30f);
        style.setColor(ImGuiCol.ButtonActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.45f);

        // -- Headers / selectable rows --
        style.setColor(ImGuiCol.Header, ACCENT_R, ACCENT_G, ACCENT_B, 0.12f);
        style.setColor(ImGuiCol.HeaderHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.22f);
        style.setColor(ImGuiCol.HeaderActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f);

        // -- Tabs --
        style.setColor(ImGuiCol.Tab, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TabHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f);
        style.setColor(ImGuiCol.TabActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.22f);
        style.setColor(ImGuiCol.TabUnfocused, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TabUnfocusedActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.15f);

        // -- Table --
        style.setColor(ImGuiCol.TableHeaderBg, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TableBorderStrong, BORDER_R, BORDER_G, BORDER_B, 0.5f);
        style.setColor(ImGuiCol.TableBorderLight, BORDER_R, BORDER_G, BORDER_B, 0.25f);
        style.setColor(ImGuiCol.TableRowBg, 0f, 0f, 0f, 0f);
        style.setColor(ImGuiCol.TableRowBgAlt, 1f, 1f, 1f, 0.02f);

        // -- Separators --
        style.setColor(ImGuiCol.Separator, BORDER_R, BORDER_G, BORDER_B, 0.4f);
        style.setColor(ImGuiCol.SeparatorHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.5f);
        style.setColor(ImGuiCol.SeparatorActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);

        // -- Scrollbar --
        style.setColor(ImGuiCol.ScrollbarBg, BG_R, BG_G, BG_B, 0.3f);
        style.setColor(ImGuiCol.ScrollbarGrab, DIM_TEXT_R, DIM_TEXT_G, DIM_TEXT_B, 0.4f);
        style.setColor(ImGuiCol.ScrollbarGrabHovered, TEXT_SEC_R, TEXT_SEC_G, TEXT_SEC_B, 0.5f);
        style.setColor(ImGuiCol.ScrollbarGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);

        // -- Widgets --
        style.setColor(ImGuiCol.CheckMark, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.SliderGrab, ACCENT_R, ACCENT_G, ACCENT_B, 0.7f);
        style.setColor(ImGuiCol.SliderGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.PlotHistogram, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);
        style.setColor(ImGuiCol.PlotHistogramHovered, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.TextSelectedBg, ACCENT_R, ACCENT_G, ACCENT_B, 0.25f);

        // -- Resize grip --
        style.setColor(ImGuiCol.ResizeGrip, ACCENT_R, ACCENT_G, ACCENT_B, 0.1f);
        style.setColor(ImGuiCol.ResizeGripHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.4f);
        style.setColor(ImGuiCol.ResizeGripActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.7f);

        // -- Nav highlight --
        style.setColor(ImGuiCol.NavHighlight, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);
    }
}
