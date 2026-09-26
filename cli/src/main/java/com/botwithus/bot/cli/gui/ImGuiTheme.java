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
    public static final float DIM_TEXT_R = 0x7a / 255f, DIM_TEXT_G = 0x7d / 255f, DIM_TEXT_B = 0x88 / 255f;

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

    // ── Design tokens (redesign round 1) ──────────────────────────────────
    // The one place the shared shell (top bar, status bar) and Normal mode take
    // their colours, sizes, radii and durations from. Colours are the navy greys
    // and status hues above, packed once; soft tints are alpha over the same hue,
    // never a new colour. Sizes are ratios of the body font (see Metrics).

    private static final float SOFT_ALPHA = 0.12f;
    private static final float SOFT_HOVER_ALPHA = 0.20f;
    private static final float BORDER_HOVER_ALPHA = 0.18f;
    private static final float SCRIM_ALPHA = 0.66f;
    private static final float SHADOW_ALPHA = 0.35f;

    public static final int COL_BG = imCol32(BG_R, BG_G, BG_B, 1f);
    public static final int COL_SURFACE = imCol32(SURFACE_R, SURFACE_G, SURFACE_B, 1f);
    public static final int COL_ELEVATED = imCol32(ELEVATED_R, ELEVATED_G, ELEVATED_B, 1f);
    public static final int COL_BORDER = imCol32(BORDER_R, BORDER_G, BORDER_B, 1f);
    public static final int COL_BORDER_HOVER = imCol32(TEXT_R, TEXT_G, TEXT_B, BORDER_HOVER_ALPHA);
    public static final int COL_SCRIM = imCol32(0x05 / 255f, 0x06 / 255f, 0x09 / 255f, SCRIM_ALPHA);
    public static final int COL_SHADOW = imCol32(0f, 0f, 0f, SHADOW_ALPHA);

    public static final int COL_FG = imCol32(TEXT_R, TEXT_G, TEXT_B, 1f);
    public static final int COL_FG2 = imCol32(TEXT_SEC_R, TEXT_SEC_G, TEXT_SEC_B, 1f);
    public static final int COL_FG3 = imCol32(DIM_TEXT_R, DIM_TEXT_G, DIM_TEXT_B, 1f);

    public static final int COL_ACCENT = imCol32(ACCENT_R, ACCENT_G, ACCENT_B, 1f);
    public static final int COL_ACCENT_HOVER = imCol32(0x6e / 255f, 0xe7 / 255f, 0x9a / 255f, 1f);
    public static final int COL_ACCENT_PRESS = imCol32(0x34 / 255f, 0xc4 / 255f, 0x6c / 255f, 1f);
    public static final int COL_ON_ACCENT = COL_BG;
    public static final int COL_ACCENT_SOFT = imCol32(ACCENT_R, ACCENT_G, ACCENT_B, SOFT_ALPHA);
    public static final int COL_ACCENT_SOFT_HOVER = imCol32(ACCENT_R, ACCENT_G, ACCENT_B, SOFT_HOVER_ALPHA);
    public static final int COL_INFO = imCol32(BLUE_R, BLUE_G, BLUE_B, 1f);
    public static final int COL_INFO_SOFT = imCol32(BLUE_R, BLUE_G, BLUE_B, SOFT_ALPHA);
    public static final int COL_WARN = imCol32(YELLOW_R, YELLOW_G, YELLOW_B, 1f);
    public static final int COL_WARN_SOFT = imCol32(YELLOW_R, YELLOW_G, YELLOW_B, SOFT_ALPHA);
    public static final int COL_DANGER = imCol32(RED_R, RED_G, RED_B, 1f);
    public static final int COL_DANGER_SOFT = imCol32(RED_R, RED_G, RED_B, SOFT_ALPHA);
    public static final int COL_DANGER_SOFT_HOVER = imCol32(RED_R, RED_G, RED_B, SOFT_HOVER_ALPHA);
    public static final int COL_FOCUS = COL_INFO;

    /** Hover and toggle feedback. */
    public static final float DURATION_FAST_S = 0.12f;
    /** Drawer, modal and toast slides; card entrance. */
    public static final float DURATION_S = 0.20f;
    /** Full period of the loading alpha pulse — the only looping animation. */
    public static final float PULSE_PERIOD_S = 1.4f;
    /** How long a toast stays up before it slides out. */
    public static final float TOAST_LIFE_S = 5f;
    /** Opacity of a disabled control. */
    public static final float DISABLED_ALPHA = 0.45f;

    /**
     * Every size the redesign uses, derived from the body font size so it scales
     * with DPI the way the font does. The ratios are the prototype's pixel values
     * over its 15 px base; {@code u} is its 4 px spacing unit.
     *
     * @param fontSize body font size in pixels, the unit all ratios multiply
     */
    public record Metrics(float fontSize) {

        private static final float UNIT = 0.25f;
        private static final float CONTROL = 2f;
        private static final float CONTROL_SMALL = 1.733f;
        private static final float TOP_BAR = 2.933f;
        private static final float STATUS_BAR = 1.733f;
        private static final float DRAWER = 23.5f;
        private static final float DRAWER_MAX_FRACTION = 0.6f;
        private static final float CARD_MIN = 19.33f;
        private static final float LANE = 1.467f;
        private static final float BAR = 0.2f;
        private static final float BAR_GAP = 0.133f;
        private static final float CHIP = 1.467f;
        private static final float ICON_TILE = 2.133f;
        private static final float INSET_MIN = 6.133f;
        private static final float DOT = 0.4f;
        private static final float RADIUS_SMALL = 0.267f;
        private static final float RADIUS = 0.4f;
        private static final float RADIUS_LARGE = 0.533f;
        private static final float RADIUS_XL = 0.667f;
        private static final float FOCUS_WIDTH = 0.133f;
        private static final float HAIRLINE = 1f;

        /** One spacing unit; {@code u(3)} is the prototype's {@code --sp-3}. */
        public float u(float units) {
            return Math.round(fontSize * UNIT) * units;
        }

        public float controlHeight() { return fontSize * CONTROL; }
        public float controlSmallHeight() { return fontSize * CONTROL_SMALL; }
        public float topBarHeight() { return fontSize * TOP_BAR; }
        public float statusBarHeight() { return fontSize * STATUS_BAR; }
        public float drawerWidth(float available) {
            return Math.min(fontSize * DRAWER, available * DRAWER_MAX_FRACTION);
        }
        public float cardMinWidth() { return fontSize * CARD_MIN; }
        public float laneHeight() { return fontSize * LANE; }
        public float barWidth() { return Math.max(HAIRLINE, fontSize * BAR); }
        public float barGap() { return Math.max(HAIRLINE, fontSize * BAR_GAP); }
        public float chipHeight() { return fontSize * CHIP; }
        public float iconTile() { return fontSize * ICON_TILE; }
        public float insetMinHeight() { return fontSize * INSET_MIN; }
        public float dot() { return fontSize * DOT; }
        public float radiusSmall() { return fontSize * RADIUS_SMALL; }
        public float radius() { return fontSize * RADIUS; }
        public float radiusLarge() { return fontSize * RADIUS_LARGE; }
        public float radiusXl() { return fontSize * RADIUS_XL; }
        public float focusWidth() { return Math.max(HAIRLINE, fontSize * FOCUS_WIDTH); }
        public float hairline() { return HAIRLINE; }
    }

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
        applyGeometry(style);
        style.scaleAllSizes(scale);
        applyWindowAndBorderColors(style);
        applyInputAndTitleColors(style);
        applyTextAndButtonColors(style);
        applyHeaderTabAndTableColors(style);
        applyScrollbarAndWidgetColors(style);
    }

    /** Window-frame rounding, padding, item spacing, border sizes. */
    private static void applyGeometry(ImGuiStyle style) {
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

        style.setWindowBorderSize(0f);
        style.setChildBorderSize(1f);
        style.setFrameBorderSize(0f);
        style.setPopupBorderSize(1f);
        style.setTabBorderSize(0f);
    }

    private static void applyWindowAndBorderColors(ImGuiStyle style) {
        style.setColor(ImGuiCol.WindowBg, BG_R, BG_G, BG_B, 1f);
        style.setColor(ImGuiCol.ChildBg, BG_R, BG_G, BG_B, 0f);
        style.setColor(ImGuiCol.PopupBg, SURFACE_R, SURFACE_G, SURFACE_B, 0.97f);
        style.setColor(ImGuiCol.Border, BORDER_R, BORDER_G, BORDER_B, 0.6f);
        style.setColor(ImGuiCol.BorderShadow, 0f, 0f, 0f, 0f);
    }

    private static void applyInputAndTitleColors(ImGuiStyle style) {
        style.setColor(ImGuiCol.FrameBg, INPUT_BG_R, INPUT_BG_G, INPUT_BG_B, 1f);
        style.setColor(ImGuiCol.FrameBgHovered, ELEVATED_R, ELEVATED_G, ELEVATED_B, 1f);
        style.setColor(ImGuiCol.FrameBgActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.18f);
        style.setColor(ImGuiCol.TitleBg, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TitleBgActive, ELEVATED_R, ELEVATED_G, ELEVATED_B, 1f);
        style.setColor(ImGuiCol.TitleBgCollapsed, SURFACE_R, SURFACE_G, SURFACE_B, 0.6f);
    }

    private static void applyTextAndButtonColors(ImGuiStyle style) {
        style.setColor(ImGuiCol.Text, TEXT_R, TEXT_G, TEXT_B, 1f);
        style.setColor(ImGuiCol.TextDisabled, DIM_TEXT_R, DIM_TEXT_G, DIM_TEXT_B, 1f);
        style.setColor(ImGuiCol.Button, ACCENT_R, ACCENT_G, ACCENT_B, 0.18f);
        style.setColor(ImGuiCol.ButtonHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.30f);
        style.setColor(ImGuiCol.ButtonActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.45f);
    }

    private static void applyHeaderTabAndTableColors(ImGuiStyle style) {
        style.setColor(ImGuiCol.Header, ACCENT_R, ACCENT_G, ACCENT_B, 0.12f);
        style.setColor(ImGuiCol.HeaderHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.22f);
        style.setColor(ImGuiCol.HeaderActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f);

        style.setColor(ImGuiCol.Tab, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TabHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.35f);
        style.setColor(ImGuiCol.TabActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.22f);
        style.setColor(ImGuiCol.TabUnfocused, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TabUnfocusedActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.15f);

        style.setColor(ImGuiCol.TableHeaderBg, SURFACE_R, SURFACE_G, SURFACE_B, 1f);
        style.setColor(ImGuiCol.TableBorderStrong, BORDER_R, BORDER_G, BORDER_B, 0.5f);
        style.setColor(ImGuiCol.TableBorderLight, BORDER_R, BORDER_G, BORDER_B, 0.25f);
        style.setColor(ImGuiCol.TableRowBg, 0f, 0f, 0f, 0f);
        style.setColor(ImGuiCol.TableRowBgAlt, 1f, 1f, 1f, 0.02f);

        style.setColor(ImGuiCol.Separator, BORDER_R, BORDER_G, BORDER_B, 0.4f);
        style.setColor(ImGuiCol.SeparatorHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.5f);
        style.setColor(ImGuiCol.SeparatorActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);
    }

    private static void applyScrollbarAndWidgetColors(ImGuiStyle style) {
        style.setColor(ImGuiCol.ScrollbarBg, BG_R, BG_G, BG_B, 0.3f);
        style.setColor(ImGuiCol.ScrollbarGrab, DIM_TEXT_R, DIM_TEXT_G, DIM_TEXT_B, 0.4f);
        style.setColor(ImGuiCol.ScrollbarGrabHovered, TEXT_SEC_R, TEXT_SEC_G, TEXT_SEC_B, 0.5f);
        style.setColor(ImGuiCol.ScrollbarGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);

        style.setColor(ImGuiCol.CheckMark, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.SliderGrab, ACCENT_R, ACCENT_G, ACCENT_B, 0.7f);
        style.setColor(ImGuiCol.SliderGrabActive, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.PlotHistogram, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);
        style.setColor(ImGuiCol.PlotHistogramHovered, ACCENT_R, ACCENT_G, ACCENT_B, 1f);
        style.setColor(ImGuiCol.TextSelectedBg, ACCENT_R, ACCENT_G, ACCENT_B, 0.25f);

        style.setColor(ImGuiCol.ResizeGrip, ACCENT_R, ACCENT_G, ACCENT_B, 0.1f);
        style.setColor(ImGuiCol.ResizeGripHovered, ACCENT_R, ACCENT_G, ACCENT_B, 0.4f);
        style.setColor(ImGuiCol.ResizeGripActive, ACCENT_R, ACCENT_G, ACCENT_B, 0.7f);

        style.setColor(ImGuiCol.NavHighlight, ACCENT_R, ACCENT_G, ACCENT_B, 0.8f);
    }
}
