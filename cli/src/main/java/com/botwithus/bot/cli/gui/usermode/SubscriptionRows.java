package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.usermode.board.PriceBadge;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionEntry;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionGroup;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;

import imgui.ImDrawList;
import imgui.ImFont;

import java.util.List;
import java.util.Optional;

/**
 * Draws the picker's "Your subscriptions" group: its rows, the one-line note that
 * stands in for them when the catalogue is not available, and the details pane of
 * a highlighted subscription. Every failure here is phrased as a note, calmly,
 * because the local scripts below the group still work.
 */
final class SubscriptionRows {

    static final String GROUP_LABEL = "Your subscriptions";

    private static final float LINE = 1.35f;
    private static final float DESC_CH = 40f;
    private static final String NO_VALUE = "—";

    /** A one-line note in the group: an icon and the words. */
    record Note(String icon, String text) {}

    private final Controls ui;

    SubscriptionRows(Controls ui) {
        this.ui = ui;
    }

    /** The note the group shows in place of, or above, its rows; empty when the rows say it all. */
    static Optional<Note> noteFor(SubscriptionGroup group) {
        return switch (group) {
            case SubscriptionGroup.Hidden ignored -> Optional.empty();
            case SubscriptionGroup.Pending ignored -> Optional.of(new Note(Icons.CLOCK,
                    "Checking your subscriptions…"));
            case SubscriptionGroup.Unavailable u -> Optional.of(unavailableNote(u));
            case SubscriptionGroup.Listed listed -> listedNote(listed);
        };
    }

    private static Note unavailableNote(SubscriptionGroup.Unavailable u) {
        return switch (u.reason()) {
            case LAUNCHER_NOT_RUNNING -> new Note(Icons.PLUG,
                    "Start the BotWithUs launcher to see your subscriptions.");
            case NOT_SIGNED_IN -> new Note(Icons.USERS, "Sign in to the launcher to see your subscriptions.");
            case NO_ACCESS -> new Note(Icons.CROWN, "Subscribed scripts need an active BotWithUs subscription.");
            case FAILED -> new Note(Icons.INFO, "Your subscriptions could not be loaded"
                    + (u.detail().isBlank() ? "." : ": " + u.detail()));
        };
    }

    private static Optional<Note> listedNote(SubscriptionGroup.Listed listed) {
        if (listed.entries().isEmpty()) {
            return Optional.of(new Note(Icons.BOOKMARK, "No script subscriptions on this account yet."));
        }
        if (listed.stale()) {
            return Optional.of(new Note(Icons.CLOCK, "Last known list. The launcher is not answering."));
        }
        return Optional.empty();
    }

    /** Draws {@code note} as one ellipsized caption line at (x, y); returns its height. */
    float note(ImDrawList draw, Note note, float x, float y, float w) {
        ImFont font = ui.fonts().caption();
        float iconW = ui.width(font, note.icon()) + ui.m().u(2);
        ui.text(draw, font, x, y, ImGuiTheme.COL_FG3, note.icon());
        ui.text(draw, font, x + iconW, y, ImGuiTheme.COL_FG2, ui.ellipsize(font, note.text(), w - iconW));
        return font.getFontSize() * LINE;
    }

    // ── Row ────────────────────────────────────────────────────────────────

    /** Paints a subscription row's contents; the caller has painted its background. */
    void paintRow(ImDrawList draw, SubscriptionEntry s, float x, float y, float w, float h, float tile,
                  boolean on) {
        ImGuiTheme.Metrics m = ui.m();
        ui.iconTile(draw, x + m.u(2), y + m.u(2), tile, Icons.BOOKMARK,
                on ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG2,
                on ? ImGuiTheme.COL_ACCENT_SOFT : ImGuiTheme.COL_ELEVATED);
        float right = x + w - m.u(2);
        if (s.badge() != PriceBadge.NONE) {
            float chipW = ui.chipWidth(s.badge().label());
            right -= chipW;
            badge(draw, s.badge(), right, y + (h - m.chipHeight()) * 0.5f);
            right -= m.u(2);
        }
        float tx = x + m.u(2) + tile + m.u(3);
        float tw = right - tx;
        ImFont name = ui.fonts().smallMedium();
        ImFont meta = ui.fonts().caption();
        float textH = name.getFontSize() * LINE + meta.getFontSize() * LINE;
        float ty = y + (h - textH) * 0.5f;
        ui.text(draw, name, tx, ty, ImGuiTheme.COL_FG, ui.ellipsize(name, s.name(), tw));
        MetaLine line = metaLine(s);
        ui.text(draw, meta, tx, ty + name.getFontSize() * LINE, line.colour(), ui.ellipsize(meta, line.text(), tw));
    }

    private record MetaLine(String text, int colour) {}

    /** The row's second line: progress or trouble when there is any, else who wrote it and what it does. */
    private static MetaLine metaLine(SubscriptionEntry s) {
        return switch (s.state()) {
            case SubscriptionState.Installing ignored -> new MetaLine(Icons.DOWNLOAD + "  Installing…",
                    ImGuiTheme.COL_ACCENT);
            case SubscriptionState.Failed ignored -> new MetaLine("Could not install. Select for details.",
                    ImGuiTheme.COL_WARN);
            case SubscriptionState.Installed ignored -> new MetaLine(byline(s), ImGuiTheme.COL_FG2);
            case SubscriptionState.NotInstalled ignored -> new MetaLine(byline(s), ImGuiTheme.COL_FG2);
        };
    }

    private static String byline(SubscriptionEntry s) {
        String author = s.author().isBlank() ? "" : s.author();
        if (s.summary().isBlank()) {
            return author;
        }
        return author.isBlank() ? s.summary() : author + " · " + s.summary();
    }

    private void badge(ImDrawList draw, PriceBadge badge, float x, float y) {
        boolean free = badge == PriceBadge.FREE;
        ui.chip(draw, x, y, badge.label(), free ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_INFO,
                free ? ImGuiTheme.COL_ACCENT_SOFT : ImGuiTheme.COL_INFO_SOFT, 1f);
    }

    // ── Details ────────────────────────────────────────────────────────────

    /**
     * Draws the details of {@code s} top-down from (left, top) in {@code inner} width;
     * returns the y below the last line.
     */
    float details(ImDrawList draw, SubscriptionEntry s, String account, float left, float top, float inner,
                  float tile) {
        ImGuiTheme.Metrics m = ui.m();
        float cy = top;
        ui.iconTile(draw, left, cy, tile, Icons.BOOKMARK, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT);
        if (s.badge() != PriceBadge.NONE) {
            badge(draw, s.badge(), left + tile + m.u(3), cy + (tile - m.chipHeight()) * 0.5f);
        }
        cy += tile + m.u(3);
        ImFont title = ui.fonts().bodyMedium();
        ui.text(draw, title, left, cy, ImGuiTheme.COL_FG, ui.ellipsize(title, s.name(), inner));
        cy += title.getFontSize() * LINE + m.u(3);
        cy = paragraph(draw, s.summary().isBlank() ? "No description." : s.summary(), ImGuiTheme.COL_FG2,
                left, cy, inner);
        // Progress or trouble goes above the facts, so an install failure's message
        // is on screen without scrolling the pane.
        float afterState = stateBlock(draw, s.state(), account, left, cy + m.u(2), inner);
        float factsY = afterState > cy + m.u(2) ? afterState + m.u(2) : cy + m.u(3);
        return KeyValueList.draw(ui, draw, facts(s), left, factsY);
    }

    private static List<KeyValueList.Row> facts(SubscriptionEntry s) {
        return List.of(
                new KeyValueList.Row("Author", s.author().isBlank() ? NO_VALUE : s.author()),
                new KeyValueList.Row("Version", s.version().isBlank() ? NO_VALUE : s.version()),
                new KeyValueList.Row("Price", s.badge() == PriceBadge.NONE ? NO_VALUE : s.badge().label()),
                new KeyValueList.Row("Status", statusLabel(s.state())));
    }

    private static String statusLabel(SubscriptionState state) {
        return switch (state) {
            case SubscriptionState.Installed ignored -> "installed";
            case SubscriptionState.NotInstalled ignored -> "not installed";
            case SubscriptionState.Installing ignored -> "installing";
            case SubscriptionState.Failed ignored -> "install failed";
        };
    }

    /** What happens when this row is started, or why it did not work last time. */
    private float stateBlock(ImDrawList draw, SubscriptionState state, String account, float x, float y,
                             float w) {
        return switch (state) {
            case SubscriptionState.Installed ignored -> y;
            case SubscriptionState.NotInstalled ignored -> paragraph(draw,
                    "Starting installs it through the launcher, then runs it on " + account + ".",
                    ImGuiTheme.COL_FG3, x, y, w);
            case SubscriptionState.Installing ignored -> paragraph(draw,
                    "Installing through the launcher. It starts on " + account + " when it arrives.",
                    ImGuiTheme.COL_FG2, x, y, w);
            case SubscriptionState.Failed failed -> failure(draw, failed.message(), x, y, w);
        };
    }

    private float failure(ImDrawList draw, String message, float x, float y, float w) {
        ImFont small = ui.fonts().smallMedium();
        ui.text(draw, small, x, y, ImGuiTheme.COL_WARN, Icons.WARNING + "  Could not install");
        float cy = y + small.getFontSize() * LINE;
        cy = paragraph(draw, message, ImGuiTheme.COL_FG2, x, cy, w);
        return paragraph(draw, "Start tries again.", ImGuiTheme.COL_FG3, x, cy, w);
    }

    private float paragraph(ImDrawList draw, String text, int colour, float x, float y, float w) {
        ImFont small = ui.fonts().small();
        float width = Math.min(w, ui.width(small, "0") * DESC_CH);
        float cy = y;
        for (String line : ui.wrap(small, text, width)) {
            ui.text(draw, small, x, cy, colour, line);
            cy += small.getFontSize() * LINE;
        }
        return cy;
    }
}
