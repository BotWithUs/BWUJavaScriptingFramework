package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.ImGuiTheme;

/** The icon tile's colours: red for a crash, amber for a stall, green while running, grey otherwise. */
record IconTone(int fg, int bg) {

    static IconTone of(InstalledScript s) {
        if (s.count(RunnerState.CRASHED) + s.count(RunnerState.CUT_OFF) > 0) {
            return new IconTone(ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT);
        }
        if (s.count(RunnerState.STALLED) > 0) {
            return new IconTone(ImGuiTheme.COL_WARN, ImGuiTheme.COL_WARN_SOFT);
        }
        if (s.isActive()) {
            return new IconTone(ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT);
        }
        return new IconTone(s.provenance().isLoaded() ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_FG3,
                ImGuiTheme.COL_ELEVATED);
    }
}
