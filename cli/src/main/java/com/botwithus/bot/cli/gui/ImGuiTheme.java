package com.botwithus.bot.cli.gui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;

/**
 * The BotWithUs dark theme: the colour tokens every page draws with, the
 * durations and sizes they animate and lay out by, and the imgui style setup.
 */
public final class ImGuiTheme {

    // ── Palette ────────────────────────────────────────────────────────────
    // The navy greys and status hues as 0xRRGGBB. Private: everything outside
    // this class takes a packed COL_* token instead, so a colour has one name.

    private static final int RGB_BG = 0x0d0f14;
    private static final int RGB_SURFACE = 0x14171f;
    private static final int RGB_INPUT_BG = 0x1a1e28;
    private static final int RGB_ELEVATED = 0x1f232e;
    private static final int RGB_BORDER = 0x2a2e3a;
    private static final int RGB_SCRIM = 0x050609;
    private static final int RGB_BLACK = 0x000000;
    private static final int RGB_WHITE = 0xffffff;

    private static final int RGB_FG = 0xecedf0;
    private static final int RGB_FG2 = 0x9c9ea8;
    private static final int RGB_FG3 = 0x7a7d88;

    /** Emerald: running, healthy. */
    private static final int RGB_ACCENT = 0x4ade80;
    private static final int RGB_ACCENT_HOVER = 0x6ee79a;
    private static final int RGB_ACCENT_PRESS = 0x34c46c;
    /** Blue: working on it. */
    private static final int RGB_INFO = 0x60a5fa;
    /** Amber: needs a look soon. */
    private static final int RGB_WARN = 0xfbbd23;
    /** Red: stopped against your will. */
    private static final int RGB_DANGER = 0xf87171;
    private static final int RGB_MAGENTA = 0xc084fc;
    private static final int RGB_CYAN = 0x67e8f9;

    private static final int CHANNEL_MAX = 0xFF;
    private static final float CHANNEL_SCALE = 255f;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int BLUE_SHIFT_PACKED = 16;
    private static final int ALPHA_SHIFT = 24;

    // ── Design tokens ──────────────────────────────────────────────────────
    // Where the shell and every page take their colours, sizes, radii and
    // durations from. Colours are the palette above, packed once; soft tints are
    // alpha over the same hue, never a new colour. Sizes are ratios of the body
    // font (see Metrics).

    private static final float SOFT_ALPHA = 0.12f;
    private static final float SOFT_HOVER_ALPHA = 0.20f;
    private static final float BORDER_HOVER_ALPHA = 0.18f;
    private static final float SCRIM_ALPHA = 0.66f;
    private static final float SHADOW_ALPHA = 0.35f;

    public static final int COL_BG = col(RGB_BG, 1f);
    public static final int COL_SURFACE = col(RGB_SURFACE, 1f);
    public static final int COL_ELEVATED = col(RGB_ELEVATED, 1f);
    public static final int COL_BORDER = col(RGB_BORDER, 1f);
    public static final int COL_BORDER_HOVER = col(RGB_FG, BORDER_HOVER_ALPHA);
    public static final int COL_SCRIM = col(RGB_SCRIM, SCRIM_ALPHA);
    public static final int COL_SHADOW = col(RGB_BLACK, SHADOW_ALPHA);

    public static final int COL_FG = col(RGB_FG, 1f);
    public static final int COL_FG2 = col(RGB_FG2, 1f);
    public static final int COL_FG3 = col(RGB_FG3, 1f);

    public static final int COL_ACCENT = col(RGB_ACCENT, 1f);
    public static final int COL_ACCENT_HOVER = col(RGB_ACCENT_HOVER, 1f);
    public static final int COL_ACCENT_PRESS = col(RGB_ACCENT_PRESS, 1f);
    public static final int COL_ON_ACCENT = COL_BG;
    public static final int COL_ACCENT_SOFT = col(RGB_ACCENT, SOFT_ALPHA);
    public static final int COL_ACCENT_SOFT_HOVER = col(RGB_ACCENT, SOFT_HOVER_ALPHA);
    public static final int COL_INFO = col(RGB_INFO, 1f);
    public static final int COL_INFO_SOFT = col(RGB_INFO, SOFT_ALPHA);
    public static final int COL_WARN = col(RGB_WARN, 1f);
    public static final int COL_WARN_SOFT = col(RGB_WARN, SOFT_ALPHA);
    public static final int COL_DANGER = col(RGB_DANGER, 1f);
    public static final int COL_DANGER_SOFT = col(RGB_DANGER, SOFT_ALPHA);
    public static final int COL_DANGER_SOFT_HOVER = col(RGB_DANGER, SOFT_HOVER_ALPHA);
    public static final int COL_FOCUS = COL_INFO;

    /**
     * Terminal hues, not status colours: the console's ANSI palette, its prompt
     * target and the Events tab's event names.
     */
    public static final int COL_MAGENTA = col(RGB_MAGENTA, 1f);
    public static final int COL_CYAN = col(RGB_CYAN, 1f);

    /** Hover and toggle feedback. */
    public static final float DURATION_FAST_S = 0.12f;
    /** Drawer, modal and toast slides; card entrance. */
    public static final float DURATION_S = 0.20f;
    /** Full period of the loading alpha pulse — the only looping animation. */
    public static final float PULSE_PERIOD_S = 1.4f;
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
        private static final float DRAWER = 34f;
        private static final float DRAWER_MIN = 20f;
        private static final float DRAWER_MAX_FRACTION = 0.75f;
        private static final float DRAWER_GRIP = 0.4f;
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
        /**
         * The inspector drawer's width: {@code preferred} when the user has dragged
         * it, else the default, kept between a readable minimum and a share of
         * {@code available} that leaves the page beside it usable.
         *
         * @param preferred the width the user dragged to, or {@code 0} for the default
         * @param available the width the page and drawer share
         */
        public float drawerWidth(float preferred, float available) {
            float wanted = preferred > 0f ? preferred : fontSize * DRAWER;
            float max = available * DRAWER_MAX_FRACTION;
            return Math.min(Math.max(wanted, Math.min(fontSize * DRAWER_MIN, max)), max);
        }
        public float drawerGrip() { return Math.max(HAIRLINE, fontSize * DRAWER_GRIP); }
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
     * Convert RGBA floats (0-1) to packed ImGui color integer (IM_COL32 format).
     */
    public static int imCol32(float r, float g, float b, float a) {
        return ((int)(a * 255f) << 24) | ((int)(b * 255f) << 16) | ((int)(g * 255f) << 8) | (int)(r * 255f);
    }

    /** Packs a palette {@code 0xRRGGBB} at {@code alpha} into imgui's ABGR order. */
    private static int col(int rgb, float alpha) {
        int r = (rgb >>> RED_SHIFT) & CHANNEL_MAX;
        int g = (rgb >>> GREEN_SHIFT) & CHANNEL_MAX;
        int b = rgb & CHANNEL_MAX;
        return ((int) (alpha * CHANNEL_SCALE) << ALPHA_SHIFT) | (b << BLUE_SHIFT_PACKED) | (g << GREEN_SHIFT) | r;
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

    /** Sets one style colour to palette {@code rgb} at {@code alpha}, in imgui's float form. */
    private static void setColor(ImGuiStyle style, int colorIndex, int rgb, float alpha) {
        style.setColor(colorIndex, ((rgb >>> RED_SHIFT) & CHANNEL_MAX) / CHANNEL_SCALE,
                ((rgb >>> GREEN_SHIFT) & CHANNEL_MAX) / CHANNEL_SCALE, (rgb & CHANNEL_MAX) / CHANNEL_SCALE, alpha);
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
        setColor(style, ImGuiCol.WindowBg, RGB_BG, 1f);
        setColor(style, ImGuiCol.ChildBg, RGB_BG, 0f);
        setColor(style, ImGuiCol.PopupBg, RGB_SURFACE, 0.97f);
        setColor(style, ImGuiCol.Border, RGB_BORDER, 0.6f);
        setColor(style, ImGuiCol.BorderShadow, RGB_BLACK, 0f);
    }

    private static void applyInputAndTitleColors(ImGuiStyle style) {
        setColor(style, ImGuiCol.FrameBg, RGB_INPUT_BG, 1f);
        setColor(style, ImGuiCol.FrameBgHovered, RGB_ELEVATED, 1f);
        setColor(style, ImGuiCol.FrameBgActive, RGB_ACCENT, 0.18f);
        setColor(style, ImGuiCol.TitleBg, RGB_SURFACE, 1f);
        setColor(style, ImGuiCol.TitleBgActive, RGB_ELEVATED, 1f);
        setColor(style, ImGuiCol.TitleBgCollapsed, RGB_SURFACE, 0.6f);
    }

    private static void applyTextAndButtonColors(ImGuiStyle style) {
        setColor(style, ImGuiCol.Text, RGB_FG, 1f);
        setColor(style, ImGuiCol.TextDisabled, RGB_FG3, 1f);
        setColor(style, ImGuiCol.Button, RGB_ACCENT, 0.18f);
        setColor(style, ImGuiCol.ButtonHovered, RGB_ACCENT, 0.30f);
        setColor(style, ImGuiCol.ButtonActive, RGB_ACCENT, 0.45f);
    }

    private static void applyHeaderTabAndTableColors(ImGuiStyle style) {
        setColor(style, ImGuiCol.Header, RGB_ACCENT, 0.12f);
        setColor(style, ImGuiCol.HeaderHovered, RGB_ACCENT, 0.22f);
        setColor(style, ImGuiCol.HeaderActive, RGB_ACCENT, 0.35f);

        setColor(style, ImGuiCol.Tab, RGB_SURFACE, 1f);
        setColor(style, ImGuiCol.TabHovered, RGB_ACCENT, 0.35f);
        setColor(style, ImGuiCol.TabActive, RGB_ACCENT, 0.22f);
        setColor(style, ImGuiCol.TabUnfocused, RGB_SURFACE, 1f);
        setColor(style, ImGuiCol.TabUnfocusedActive, RGB_ACCENT, 0.15f);

        setColor(style, ImGuiCol.TableHeaderBg, RGB_SURFACE, 1f);
        setColor(style, ImGuiCol.TableBorderStrong, RGB_BORDER, 0.5f);
        setColor(style, ImGuiCol.TableBorderLight, RGB_BORDER, 0.25f);
        setColor(style, ImGuiCol.TableRowBg, RGB_BLACK, 0f);
        setColor(style, ImGuiCol.TableRowBgAlt, RGB_WHITE, 0.02f);

        setColor(style, ImGuiCol.Separator, RGB_BORDER, 0.4f);
        setColor(style, ImGuiCol.SeparatorHovered, RGB_ACCENT, 0.5f);
        setColor(style, ImGuiCol.SeparatorActive, RGB_ACCENT, 0.8f);
    }

    private static void applyScrollbarAndWidgetColors(ImGuiStyle style) {
        setColor(style, ImGuiCol.ScrollbarBg, RGB_BG, 0.3f);
        setColor(style, ImGuiCol.ScrollbarGrab, RGB_FG3, 0.4f);
        setColor(style, ImGuiCol.ScrollbarGrabHovered, RGB_FG2, 0.5f);
        setColor(style, ImGuiCol.ScrollbarGrabActive, RGB_ACCENT, 0.8f);

        setColor(style, ImGuiCol.CheckMark, RGB_ACCENT, 1f);
        setColor(style, ImGuiCol.SliderGrab, RGB_ACCENT, 0.7f);
        setColor(style, ImGuiCol.SliderGrabActive, RGB_ACCENT, 1f);
        setColor(style, ImGuiCol.PlotHistogram, RGB_ACCENT, 0.8f);
        setColor(style, ImGuiCol.PlotHistogramHovered, RGB_ACCENT, 1f);
        setColor(style, ImGuiCol.TextSelectedBg, RGB_ACCENT, 0.25f);

        setColor(style, ImGuiCol.ResizeGrip, RGB_ACCENT, 0.1f);
        setColor(style, ImGuiCol.ResizeGripHovered, RGB_ACCENT, 0.4f);
        setColor(style, ImGuiCol.ResizeGripActive, RGB_ACCENT, 0.7f);

        setColor(style, ImGuiCol.NavHighlight, RGB_ACCENT, 0.8f);
    }
}
