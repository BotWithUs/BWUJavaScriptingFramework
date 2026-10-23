package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.ImGuiTheme;

/**
 * The five status colours a client card uses for chips, icon tiles and meta
 * lines. Colour is status, never decoration: emerald is running, blue is
 * working on it, amber needs a look, red stopped against your will.
 */
enum CardTone {
    RUN(ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT),
    IDLE(ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED),
    INFO(ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT),
    WARN(ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT),
    ERR(ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT);

    private final int fg;
    private final int bg;

    CardTone(int fg, int bg) {
        this.fg = fg;
        this.bg = bg;
    }

    int fg() {
        return fg;
    }

    int bg() {
        return bg;
    }
}
