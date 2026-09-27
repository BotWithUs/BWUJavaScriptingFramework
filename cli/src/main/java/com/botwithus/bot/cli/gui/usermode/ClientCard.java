package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.usermode.CardText.Chip;
import com.botwithus.bot.cli.gui.usermode.CardText.Note;
import com.botwithus.bot.cli.gui.usermode.CardText.Stat;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ResumeSwitch;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;
import java.util.Optional;

/**
 * One client card, keyed by the client's account: its name, short UUID and
 * world with a status chip; its stats, or a note while it is not connected; a
 * row per script; and a footer with "Resume after restart" and the action its
 * state calls for.
 *
 * <p>Cards are as tall as their content, so a client running three scripts is
 * taller than an idle one. Actions that change the client go straight to
 * {@link ClientActions}; the two that open something (the picker, the
 * inspector) are handed back as an {@link Intent} for the page to act on.</p>
 */
final class ClientCard {

    /** What the page must do after a card was clicked. */
    sealed interface Intent {
        record None() implements Intent { }

        record Select() implements Intent { }

        record StartScript() implements Intent { }

        /** Open the inspector on {@code scriptName}. */
        record Configure(String scriptName) implements Intent { }

        /** Open Management on {@code managementScript}, which manages a script on the card. */
        record OpenManagement(String managementScript) implements Intent { }
    }

    private static final float NAME_LINE = 1.3f;
    private static final float SUB_LINE = 1.5f;
    private static final float LABEL_LINE = 1.3f;
    private static final float VALUE_LINE = 1.4f;
    private static final float NOTE_TITLE_LINE = 1.45f;
    private static final float NOTE_BODY_LINE = 1.45f;
    private static final float NOTE_ICON_EM = 1.067f;
    private static final float SKELETON_EM = 0.667f;
    private static final float SKELETON_WIDE = 0.6f;
    private static final float SKELETON_NARROW = 0.4f;
    private static final float SKELETON_DIM = 0.45f;
    private static final float BEAT_PERIOD_S = 2f;
    private static final float BEAT_CENTER = 0.85f;
    private static final float BEAT_HALF_WIDTH = 0.15f;
    private static final float BEAT_LOW = 0.35f;
    private static final float APPEAR_SLIDE_EM = 0.267f;
    /** A closed card's body shows at 62% opacity. */
    private static final float CLOSED_BODY_VEIL = 0.38f;
    private static final String RESUME_LABEL = "Resume after restart";
    private static final String RESUME_TOOLTIP =
            "Remember the running scripts for this account and restart them after the game client restarts";

    private final Controls ui;
    private final CardWidgets widgets;
    private final ScriptRowView rows;

    ClientCard(Controls ui) {
        this.ui = ui;
        this.widgets = new CardWidgets(ui);
        this.rows = new ScriptRowView(ui, widgets);
    }

    /** Height of {@code view}'s card at width {@code w}. */
    float height(ClientView view, float w) {
        return headHeight() + bodyHeight(view, w) + footerHeight();
    }

    /**
     * Draws the card with its top-left at (x, y).
     *
     * @param appear 0..1 entrance progress; below 1 the card fades and slides in
     */
    Intent render(ClientView view, float x, float y, float w, boolean selected, float appear,
                  ClientActions actions) {
        float slide = (1f - ui.motion().ease(appear)) * ui.fonts().body().getFontSize() * APPEAR_SLIDE_EM;
        float top = y + slide;
        float bodyH = bodyHeight(view, w);
        float h = headHeight() + bodyH + footerHeight();
        ImDrawList draw = ImGui.getWindowDrawList();
        boolean hovered = ImGui.isMouseHoveringRect(x, top, x + w, top + h) && ImGui.isWindowHovered();
        paintFrame(draw, view, x, top, w, h, selected, hovered);
        paintHead(draw, view, x, top, w);
        float bodyTop = top + headHeight();
        Intent intent = renderBody(view, x, bodyTop, w, actions);
        if (view.isClosed()) {
            draw.addRectFilled(x + 1f, bodyTop, x + w - 1f, bodyTop + bodyH,
                    Controls.scaleAlpha(ImGuiTheme.COL_SURFACE, CLOSED_BODY_VEIL));
        }
        Intent footer = renderFooter(view, x, bodyTop + bodyH, w, actions);
        if (appear < 1f) {
            draw.addRectFilled(x - 1f, top - 1f, x + w + 1f, top + h + 1f,
                    Controls.scaleAlpha(ImGuiTheme.COL_BG, 1f - appear));
        }
        Intent chosen = isNone(intent) ? footer : intent;
        if (isNone(chosen) && hovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) {
            return new Intent.Select();
        }
        return chosen;
    }

    private static boolean isNone(Intent intent) {
        return switch (intent) {
            case Intent.None _ -> true;
            case Intent.Select _, Intent.StartScript _, Intent.Configure _, Intent.OpenManagement _ -> false;
        };
    }

    // ── Frame + head ───────────────────────────────────────────────────────

    private void paintFrame(ImDrawList draw, ClientView view, float x, float y, float w, float h,
                            boolean selected, boolean hovered) {
        float r = ui.m().radiusLarge();
        float t = ui.motion().step("card:" + view.id().value(), hovered ? 1f : 0f, 1f / ImGuiTheme.DURATION_FAST_S);
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_SURFACE, r);
        int border = selected ? ImGuiTheme.COL_ACCENT
                : Controls.lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, t);
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, border, r);
    }

    private float headHeight() {
        return ui.m().u(3) + ui.fonts().bodyMedium().getFontSize() * NAME_LINE
                + ui.fonts().monoCaption().getFontSize() * SUB_LINE;
    }

    private void paintHead(ImDrawList draw, ClientView view, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        Chip chip = CardText.chip(view);
        float chipW = ui.chipWidth(chip.label());
        float right = x + w - m.u(3);
        float left = x + m.u(4);
        float textW = right - chipW - m.u(2) - left;
        float top = y + m.u(3);
        ImFont name = ui.fonts().bodyMedium();
        int nameCol = view.account().isPresent() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ui.text(draw, name, left, top, nameCol, ui.ellipsize(name, CardText.title(view), textW));
        ImFont mono = ui.fonts().monoCaption();
        float subY = top + name.getFontSize() * NAME_LINE;
        ui.text(draw, mono, left, subY, ImGuiTheme.COL_FG2, ui.ellipsize(mono, CardText.subLine(view), textW));
        float dot = chip.tone() == CardTone.RUN ? beat() : 1f;
        ui.chip(draw, right - chipW, top + 1f, chip.label(), chip.tone().fg(), chip.tone().bg(), dot);
    }

    /** The running dot's heartbeat: steady, with one soft dip late in each 2 s cycle. */
    private static float beat() {
        float phase = (float) (ImGui.getTime() % BEAT_PERIOD_S) / BEAT_PERIOD_S;
        float distance = Math.abs(phase - BEAT_CENTER) / BEAT_HALF_WIDTH;
        return distance >= 1f ? 1f : BEAT_LOW + (1f - BEAT_LOW) * distance;
    }

    // ── Body: stats or note, then the script rows ──────────────────────────

    private float bodyHeight(ClientView view, float w) {
        float h = view.state().isConnected() ? statsHeight() : noteHeight(view, w);
        return h + rowsHeight(view, w);
    }

    private Intent renderBody(ClientView view, float x, float y, float w, ClientActions actions) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float rowsTop;
        switch (view.state()) {
            case ClientState.Connected connected -> {
                paintStats(draw, CardText.stats(view, connected), x, y, w);
                rowsTop = y + statsHeight();
            }
            case ClientState.Identifying _, ClientState.NotResponding _, ClientState.Closed _,
                 ClientState.Resuming _ -> {
                CardText.note(view).ifPresent(note -> paintNote(draw, view, note, x, y, w));
                rowsTop = y + noteHeight(view, w);
            }
        }
        return renderRows(view, x, rowsTop, w, actions);
    }

    private float statsHeight() {
        return ui.m().u(3) * 2f + ui.fonts().caption().getFontSize() * LABEL_LINE
                + ui.fonts().small().getFontSize() * VALUE_LINE;
    }

    private void paintStats(ImDrawList draw, List<Stat> stats, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float left = x + m.u(4);
        float colW = (x + w - m.u(3) - left) / stats.size();
        float labelY = y + m.u(3);
        float valueY = labelY + ui.fonts().caption().getFontSize() * LABEL_LINE;
        ImFont value = ui.fonts().monoSmall();
        ImFont unit = ui.fonts().monoCaption();
        for (int i = 0; i < stats.size(); i++) {
            Stat stat = stats.get(i);
            float cx = left + i * colW + (i == 0 ? 0f : m.u(3));
            if (i > 0) {
                float divX = left + i * colW;
                draw.addLine(divX, labelY, divX, valueY + value.getFontSize() * VALUE_LINE, ImGuiTheme.COL_BORDER,
                        m.hairline());
            }
            ui.text(draw, ui.fonts().caption(), cx, labelY, ImGuiTheme.COL_FG2, stat.label());
            ui.text(draw, value, cx, valueY, ImGuiTheme.COL_FG, stat.value());
            if (!stat.unit().isEmpty()) {
                float ux = cx + ui.width(value, stat.value()) + m.u(0.5f);
                ui.text(draw, unit, ux, valueY + value.getFontSize() - unit.getFontSize(), ImGuiTheme.COL_FG2,
                        stat.unit());
            }
        }
    }

    private float noteTextWidth(float w) {
        ImGuiTheme.Metrics m = ui.m();
        return Math.max(1f, w - m.u(4) - m.u(3) - noteIconColumn() - m.u(2));
    }

    private float noteIconColumn() {
        return ui.fonts().body().getFontSize() * NOTE_ICON_EM;
    }

    private float noteHeight(ClientView view, float w) {
        Optional<Note> note = CardText.note(view);
        if (note.isEmpty()) {
            return 0f;
        }
        int lines = ui.wrap(ui.fonts().caption(), note.get().body(), noteTextWidth(w)).size();
        return ui.m().u(3) * 2f + ui.fonts().smallMedium().getFontSize() * NOTE_TITLE_LINE
                + lines * ui.fonts().caption().getFontSize() * NOTE_BODY_LINE;
    }

    private void paintNote(ImDrawList draw, ClientView view, Note note, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float left = x + m.u(4);
        float top = y + m.u(3);
        ImFont title = ui.fonts().smallMedium();
        float titleH = title.getFontSize() * NOTE_TITLE_LINE;
        paintNoteIcon(draw, view.state(), note.tone(), left, top, titleH);
        float tx = left + noteIconColumn() + m.u(2);
        ui.textCentredY(draw, title, tx, top, titleH, ImGuiTheme.COL_FG, note.title());
        ImFont body = ui.fonts().caption();
        float lineH = body.getFontSize() * NOTE_BODY_LINE;
        float ly = top + titleH;
        for (String line : ui.wrap(body, note.body(), noteTextWidth(w))) {
            ui.textCentredY(draw, body, tx, ly, lineH, ImGuiTheme.COL_FG2, line);
            ly += lineH;
        }
    }

    /** A spinner while the host is working on it, else the state's icon. */
    private void paintNoteIcon(ImDrawList draw, ClientState state, CardTone tone, float x, float y, float h) {
        float col = noteIconColumn();
        switch (state) {
            case ClientState.Identifying _, ClientState.NotResponding _ ->
                    widgets.spinner(draw, x + col * 0.5f, y + h * 0.5f, tone);
            case ClientState.Closed _ -> icon(draw, Icons.POWER, ImGuiTheme.COL_FG2, x, y, h);
            case ClientState.Resuming _ -> icon(draw, Icons.LINK, tone.fg(), x, y, h);
            case ClientState.Connected _ -> { }
        }
    }

    private void icon(ImDrawList draw, String glyph, int col, float x, float y, float h) {
        ImFont font = ui.fonts().caption();
        float gx = x + (noteIconColumn() - ui.width(font, glyph)) * 0.5f;
        ui.textCentredY(draw, font, gx, y, h, col, glyph);
    }

    private float rowsHeight(ClientView view, float w) {
        if (view.scripts().isEmpty()) {
            return isIdentifying(view) ? ui.m().hairline() + skeletonHeight() : 0f;
        }
        float h = 0f;
        for (ScriptRow row : view.scripts()) {
            h += ui.m().hairline() + rows.height(view, row, w);
        }
        return h;
    }

    private Intent renderRows(ClientView view, float x, float y, float w, ClientActions actions) {
        ImDrawList draw = ImGui.getWindowDrawList();
        if (view.scripts().isEmpty()) {
            if (isIdentifying(view)) {
                divider(draw, x, y, w);
                paintSkeletonRow(draw, x, y + ui.m().hairline(), w);
            }
            return new Intent.None();
        }
        Intent intent = new Intent.None();
        float ry = y;
        for (ScriptRow row : view.scripts()) {
            divider(draw, x, ry, w);
            ry += ui.m().hairline();
            Intent clicked = rows.render(view, row, x, ry, w, actions);
            if (!isNone(clicked)) {
                intent = clicked;
            }
            ry += rows.height(view, row, w);
        }
        return intent;
    }

    private void divider(ImDrawList draw, float x, float y, float w) {
        draw.addLine(x + 1f, y + 0.5f, x + w - 1f, y + 0.5f, ImGuiTheme.COL_BORDER, ui.m().hairline());
    }

    private static boolean isIdentifying(ClientView view) {
        return switch (view.state()) {
            case ClientState.Identifying _ -> true;
            case ClientState.Connected _, ClientState.NotResponding _, ClientState.Closed _,
                 ClientState.Resuming _ -> false;
        };
    }

    private float skeletonHeight() {
        return ui.m().u(3) * 2f + ui.m().iconTile();
    }

    private void paintSkeletonRow(ImDrawList draw, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float breathe = ui.motion().pulse(1.0 / ImGuiTheme.PULSE_PERIOD_S);
        int col = Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, 1f - (1f - SKELETON_DIM) * breathe);
        float tile = m.iconTile();
        float left = x + m.u(4);
        float top = y + m.u(3);
        draw.addRectFilled(left, top, left + tile, top + tile, col, m.radius());
        float tx = left + tile + m.u(3);
        float tw = x + w - m.u(3) - tx;
        float barH = ui.fonts().body().getFontSize() * SKELETON_EM;
        float gap = m.u(2);
        float by = top + (tile - barH * 2f - gap) * 0.5f;
        draw.addRectFilled(tx, by, tx + tw * SKELETON_WIDE, by + barH, col, m.radiusSmall());
        float by2 = by + barH + gap;
        draw.addRectFilled(tx, by2, tx + tw * SKELETON_NARROW, by2 + barH, col, m.radiusSmall());
    }

    // ── Footer ─────────────────────────────────────────────────────────────

    private float footerHeight() {
        return ui.m().hairline() + ui.m().u(2) * 2f + ui.m().controlHeight();
    }

    private Intent renderFooter(ClientView view, float x, float y, float w, ClientActions actions) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        divider(draw, x, y, w);
        float rowY = y + m.hairline() + m.u(2);
        float rowH = m.controlHeight();
        renderResume(view, x + m.u(4), rowY, rowH, actions);
        return renderFooterAction(view, x + w - m.u(3), rowY, rowH, actions);
    }

    private void renderResume(ClientView view, float x, float y, float h, ClientActions actions) {
        switch (view.resume()) {
            case ResumeSwitch.Available available -> {
                ImGui.setCursorScreenPos(x, y);
                if (widgets.labelledSwitch("##resume" + view.id().value(), RESUME_LABEL, available.isOn(), h,
                        RESUME_TOOLTIP)) {
                    actions.setResumeAfterRestart(view.id(), !available.isOn());
                }
            }
            case ResumeSwitch.Unavailable unavailable ->
                    ui.textCentredY(ImGui.getWindowDrawList(), ui.fonts().caption(), x, y, h, ImGuiTheme.COL_FG2,
                            unavailable.reason());
        }
    }

    /** The one button on the footer's right, if the client's state calls for one. */
    private Intent renderFooterAction(ClientView view, float right, float y, float h, ClientActions actions) {
        String id = "##" + view.id().value();
        switch (view.state()) {
            case ClientState.Connected _ -> {
                if (view.scripts().isEmpty()) {
                    return button("start" + id, Icons.PLAY, "Start script", Tone.SOFT, right, y, h)
                            ? new Intent.StartScript() : new Intent.None();
                }
                float small = ui.m().controlSmallHeight();
                return button("add" + id, Icons.PLUS, "Add", Tone.GHOST, right, y + (h - small) * 0.5f, small)
                        ? new Intent.StartScript() : new Intent.None();
            }
            case ClientState.NotResponding _ -> {
                if (button("retry" + id, Icons.REDO, "Retry now", Tone.GHOST, right, y, h)) {
                    actions.retryNow(view.id());
                }
            }
            case ClientState.Closed _ -> {
                if (button("dismiss" + id, null, "Dismiss", Tone.GHOST, right, y, h)) {
                    actions.forget(view.id());
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip("Remove this card. Its saved scripts are kept.");
                }
            }
            case ClientState.Identifying _, ClientState.Resuming _ -> { }
        }
        return new Intent.None();
    }

    private boolean button(String id, String icon, String label, Tone tone, float right, float y, float h) {
        ImGui.setCursorScreenPos(right - ui.buttonWidth(icon, label, tone), y);
        return ui.button(id, icon, label, tone, true, h);
    }
}
