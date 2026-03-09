package com.botwithus.bot.cli.gui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

/**
 * Color constants and imgui style setup matching the botwithus.com dark theme.
 * All colors in 0.0-1.0 float range.
 */
public final class ImGuiTheme {

    // Background & surface (dark Bootstrap-style)
    public static final float BG_R = 0x12 / 255f, BG_G = 0x12 / 255f, BG_B = 0x17 / 255f;
    public static final float INPUT_BG_R = 0x1c / 255f, INPUT_BG_G = 0x1e / 255f, INPUT_BG_B = 0x26 / 255f;
    public static final float SELECTION_R = 0x2a / 255f, SELECTION_G = 0x2e / 255f, SELECTION_B = 0x3a / 255f;

    // Text
    public static final float TEXT_R = 0xf0 / 255f, TEXT_G = 0xf0 / 255f, TEXT_B = 0xf0 / 255f;
    public static final float DIM_TEXT_R = 0x80 / 255f, DIM_TEXT_G = 0x80 / 255f, DIM_TEXT_B = 0x88 / 255f;

    // Accent — BotWithUs brand blue (#4a90e2)
    public static final float ACCENT_R = 0x4a / 255f, ACCENT_G = 0x90 / 255f, ACCENT_B = 0xe2 / 255f;

    // ANSI colors
    public static final float RED_R = 0xdc / 255f, RED_G = 0x35 / 255f, RED_B = 0x45 / 255f;
    public static final float GREEN_R = 0x28 / 255f, GREEN_G = 0xa7 / 255f, GREEN_B = 0x45 / 255f;
    public static final float YELLOW_R = 0xf0 / 255f, YELLOW_G = 0xad / 255f, YELLOW_B = 0x4e / 255f;
    public static final float BLUE_R = 0x4a / 255f, BLUE_G = 0x90 / 255f, BLUE_B = 0xe2 / 255f;
    public static final float MAGENTA_R = 0xc0 / 255f, MAGENTA_G = 0x84 / 255f, MAGENTA_B = 0xfc / 255f;
    public static final float CYAN_R = 0x67 / 255f, CYAN_G = 0xe8 / 255f, CYAN_B = 0xf9 / 255f;

    private ImGuiTheme() {}

    /**
     * Map SGR color code (30-37) to float[]{r, g, b}.
     */
    public static float[] ansiColorFloat(int code) {
        return switch (code) {
            case 30 -> new float[]{BG_R, BG_G, BG_B};           // black → background
            case 31 -> new float[]{RED_R, RED_G, RED_B};
            case 32 -> new float[]{GREEN_R, GREEN_G, GREEN_B};
            case 33 -> new float[]{YELLOW_R, YELLOW_G, YELLOW_B};
            case 34 -> new float[]{BLUE_R, BLUE_G, BLUE_B};
            case 35 -> new float[]{MAGENTA_R, MAGENTA_G, MAGENTA_B};
            case 36 -> new float[]{CYAN_R, CYAN_G, CYAN_B};
            case 37 -> new float[]{TEXT_R, TEXT_G, TEXT_B};       // white → text
            default -> new float[]{TEXT_R, TEXT_G, TEXT_B};
        };
    }

    /**
     * Apply the dark theme to the current imgui context with DPI scale factor of 1.0.
     */
    public static void apply() {
        apply(1.0f);
    }

    /**
     * Apply the dark theme to the current imgui context, scaling padding/sizes by the given factor.
     */
    public static void apply(float scale) {
        ImGuiStyle style = ImGui.getStyle();

        // Rounding
        style.setWindowRounding(0f);
        style.setFrameRounding(4f * scale);
        style.setScrollbarRounding(4f * scale);
        style.setGrabRounding(2f * scale);
        style.setTabRounding(4f * scale);

        // Padding
        style.setWindowPadding(8f * scale, 8f * scale);
        style.setFramePadding(6f * scale, 4f * scale);
        style.setItemSpacing(8f * scale, 4f * scale);
        style.setItemInnerSpacing(4f * scale, 4f * scale);
        style.setScrollbarSize(12f * scale);

        // -- Window & child backgrounds --
        style.setColor(ImGuiCol.WindowBg, BG_R, BG_G, BG_B, 1f);
        style.setColor(ImGuiCol.ChildBg, BG_R, BG_G, BG_B, 1f);
        style.setColor(ImGuiCol.PopupBg, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 0.95f);

        // -- Borders --
        style.setColor(ImGuiCol.Border, 1f, 1f, 1f, 0.1f);

        // -- Input frames --
        style.setColor(ImGuiCol.FrameBg, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 1f);
        style.setColor(ImGuiCol.FrameBgHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.15f);
        style.setColor(ImGuiCol.FrameBgActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.25f);

        // -- Title bar --
        style.setColor(ImGuiCol.TitleBg, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 1f);
        style.setColor(ImGuiCol.TitleBgActive, 0x1a / 255f, 0x1e / 255f, 0x2c / 255f, 1f);

        // -- Text --
        style.setColor(ImGuiCol.Text, TEXT_R, TEXT_G, TEXT_B, 1f);
        style.setColor(ImGuiCol.TextDisabled, DIM_TEXT_R, DIM_TEXT_G, DIM_TEXT_B, 1f);

        // -- Buttons (brand blue) --
        style.setColor(ImGuiCol.Button, ACCENT_R, ACCENT_G, ACCENT_B, 0.65f);
        style.setColor(ImGuiCol.ButtonHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.85f);
        style.setColor(ImGuiCol.ButtonActive, ACCENT_R, ACCENT_G, ACCENT_B, 1f);

        // -- Headers / selectable rows --
        style.setColor(ImGuiCol.Header, ACCENT_R, ACCENT_G, ACCENT_B, 0.2f);
        style.setColor(ImGuiCol.HeaderHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f);
        style.setColor(ImGuiCol.HeaderActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.5f);

        // -- Tabs --
        style.setColor(ImGuiCol.Tab, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 1f);
        style.setColor(ImGuiCol.TabHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.5f);
        style.setColor(ImGuiCol.TabActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.7f);
        style.setColor(ImGuiCol.TabUnfocused, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 1f);
        style.setColor(ImGuiCol.TabUnfocusedActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.4f);

        // -- Table --
        style.setColor(ImGuiCol.TableHeaderBg, 0x1a / 255f, 0x1e / 255f, 0x2c / 255f, 1f);
        style.setColor(ImGuiCol.TableBorderStrong, 1f, 1f, 1f, 0.1f);
        style.setColor(ImGuiCol.TableBorderLight, 1f, 1f, 1f, 0.06f);
        style.setColor(ImGuiCol.TableRowBg, 0f, 0f, 0f, 0f);
        style.setColor(ImGuiCol.TableRowBgAlt, 1f, 1f, 1f, 0.03f);

        // -- Separators --
        style.setColor(ImGuiCol.Separator, 1f, 1f, 1f, 0.1f);

        // -- Scrollbar --
        style.setColor(ImGuiCol.ScrollbarBg, BG_R, BG_G, BG_B, 0.5f);
        style.setColor(ImGuiCol.ScrollbarGrab, 1f, 1f, 1f, 0.3f);
        style.setColor(ImGuiCol.ScrollbarGrabHovered, 1f, 1f, 1f, 0.5f);
        style.setColor(ImGuiCol.ScrollbarGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 1f);

        // -- Widgets --
        style.setColor(ImGuiCol.CheckMark, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.SliderGrab, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);
        style.setColor(ImGuiCol.SliderGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.PlotHistogram, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.PlotHistogramHovered, ACCENT_R + 0.1f, ACCENT_G + 0.1f, ACCENT_B + 0.1f, 1f);
        style.setColor(ImGuiCol.TextSelectedBg, ACCENT_R, ACCENT_G, ACCENT_B, 0.3f);
    }
}
