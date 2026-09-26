package com.botwithus.bot.cli.gui;

import imgui.ImFont;

/**
 * The fonts the shared shell and Normal mode draw with. Four sizes only —
 * caption, small, body and title (12 / 13 / 15 / 20 px at 100% scale) — in Inter
 * Regular and Medium, plus JetBrains Mono for pipe names, worlds, timings, ids and
 * the status bar. There is no JetBrains Mono Medium in the bundle, so mono is
 * always Regular.
 *
 * <p>Advanced-mode panels keep drawing with the atlas default font, which is loaded
 * separately so their size does not change.</p>
 */
public record UiFonts(
        ImFont caption,
        ImFont captionMedium,
        ImFont small,
        ImFont smallMedium,
        ImFont body,
        ImFont bodyMedium,
        ImFont titleMedium,
        ImFont monoCaption,
        ImFont monoSmall) {

    /** Token sizes at 100% scale, in pixels. */
    public static final float CAPTION_PX = 12f;
    public static final float SMALL_PX = 13f;
    public static final float BODY_PX = 15f;
    public static final float TITLE_PX = 20f;

    /** Sizes and spacing for the current scale, derived from the body font. */
    public ImGuiTheme.Metrics metrics() {
        return new ImGuiTheme.Metrics(body.getFontSize());
    }
}
