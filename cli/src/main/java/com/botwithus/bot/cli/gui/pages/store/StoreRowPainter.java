package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry.Pricing;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * One row of the Store list, and the columns every row and the header share:
 * tick box, star, icon, name and author over the tagline, price, where it stands
 * on this PC, and the row's own button. Below {@link #COMPACT_NAME_EM} of room for
 * the name, the price and state columns fold into the second line instead.
 */
final class StoreRowPainter {

    /** fa-arrow-up; the Update button's glyph. */
    static final String ARROW_UP = "\uF062";

    private static final float ROW_EM = 3.467f;
    private static final float HEADER_EM = 2.133f;
    private static final float PICK_EM = 1.333f;
    private static final float ICON_EM = 2f;
    private static final float PRICE_EM = 5.6f;
    private static final float STATE_EM = 7.867f;
    private static final float ACTION_EM = 6.667f;
    private static final float COMPACT_NAME_EM = 13f;
    private static final float DIMMED_ALPHA = 0.55f;
    private static final float LINE_HEIGHT = 1.45f;
    private static final float TIGHT_LINE = 1.2f;

    /** The x of each column, in screen space, for one list width. */
    record Columns(float pick, float star, float icon, float name, float nameW, float price, float state,
                   float action, float actionW, boolean isCompact) {}

    /** What happened in a row this frame. */
    enum Click { NONE, PICK, STAR, ACTION, ROW }

    private final StoreWidgets w;
    private final Controls ui;

    StoreRowPainter(StoreWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    float rowHeight() {
        return fs() * ROW_EM;
    }

    float headerHeight() {
        return fs() * HEADER_EM;
    }

    private float fs() {
        return ui.fonts().body().getFontSize();
    }

    /** The columns for a list whose box spans x..x+width. */
    Columns columns(float x, float width) {
        ImGuiTheme.Metrics m = ui.m();
        float gap = m.u(3);
        float pick = x + m.u(4);
        float star = pick + fs() * PICK_EM + gap;
        float icon = star + w.starSize() + gap;
        float name = icon + fs() * ICON_EM + gap;
        float actionW = fs() * ACTION_EM;
        float action = x + width - m.u(3) - actionW;
        float wide = action - gap - fs() * STATE_EM - gap - fs() * PRICE_EM - gap - name;
        boolean isCompact = wide < fs() * COMPACT_NAME_EM;
        float nameW = isCompact ? action - gap - name : wide;
        float price = name + nameW + gap;
        float state = price + fs() * PRICE_EM + gap;
        return new Columns(pick, star, icon, name, nameW, price, state, action, actionW, isCompact);
    }

    /** The caption row over the list. */
    void header(ImDrawList draw, Columns c, float y) {
        float h = headerHeight();
        ImFont font = ui.fonts().captionMedium();
        ui.textCentredY(draw, font, c.name(), y, h, ImGuiTheme.COL_FG2, "Script");
        if (!c.isCompact()) {
            ui.textCentredY(draw, font, c.price(), y, h, ImGuiTheme.COL_FG2, "Price");
            ui.textCentredY(draw, font, c.state(), y, h, ImGuiTheme.COL_FG2, "On this PC");
        }
    }

    /**
     * Draws {@code row} with its top at {@code y}, spanning x0..x1, and reports what
     * was clicked. The controls in the row win over the row itself.
     */
    Click row(StoreRow row, Columns c, float x0, float x1, float y, boolean isSelected, boolean isPicked,
              boolean canInstall) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float h = rowHeight();
        boolean isHovered = ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x0, y, x1, y + h);
        int bg = isSelected ? StoreWidgets.COL_ROW_SELECTED : isHovered ? StoreWidgets.COL_ROW_HOVER : 0;
        if (bg != 0) {
            draw.addRectFilled(x0, y, x1, y + h, bg);
        }
        Click click = controls(row, c, y, h, isPicked, canInstall);
        paintText(draw, row, c, y, h);
        if (click == Click.NONE && isHovered && ImGui.isMouseClicked(0) && !ImGui.isAnyItemHovered()) {
            click = Click.ROW;
        }
        return click;
    }

    private Click controls(StoreRow row, Columns c, float y, float h, boolean isPicked, boolean canInstall) {
        Click click = Click.NONE;
        ImGui.setCursorScreenPos(c.pick(), y + (h - ui.tickBoxSize()) * 0.5f);
        if (ui.tickBox("##pick", isPicked, canInstall && row.isInstallable())) {
            click = Click.PICK;
        }
        ImGui.setCursorScreenPos(c.star(), y + (h - w.starSize()) * 0.5f);
        if (w.star("##star", row.isFavourite())) {
            click = Click.STAR;
        }
        float bh = ui.m().controlSmallHeight();
        if (actionButton(row, c.action() + c.actionW(), y + (h - bh) * 0.5f, bh, canInstall)) {
            click = Click.ACTION;
        }
        return click;
    }

    /** The row's own button, right-aligned so its right edge sits at {@code right}. */
    boolean actionButton(StoreRow row, float right, float y, float h, boolean canInstall) {
        RowPresentation.Action action = RowPresentation.action(row);
        return switch (action) {
            case OPEN -> link(right, y, h, Icons.FOLDER_OPEN, "Open", true);
            case CANNOT_INSTALL -> link(right, y, h, null, "Can’t install", false);
            case UPDATE -> button(right, y, h, ARROW_UP, "Update", Tone.SOFT, canInstall);
            case INSTALL -> button(right, y, h, Icons.DOWNLOAD, "Install", Tone.GHOST, canInstall);
            case INSTALLING -> button(right, y, h, null, "Installing…", Tone.GHOST, false);
        };
    }

    private boolean link(float right, float y, float h, String icon, String label, boolean enabled) {
        ImGui.setCursorScreenPos(right - w.linkWidth(icon, label), y);
        boolean clicked = w.link("##act", icon, label, enabled, h);
        if (enabled && ImGui.isItemHovered()) {
            ImGui.setTooltip("Open in Installed scripts");
        }
        return clicked;
    }

    private boolean button(float right, float y, float h, String icon, String label, Tone tone, boolean enabled) {
        ImGui.setCursorScreenPos(right - ui.buttonWidth(icon, label, tone), y);
        return ui.button("##act", icon, label, tone, enabled, h);
    }

    // ── Text columns ───────────────────────────────────────────────────────

    private void paintText(ImDrawList draw, StoreRow row, Columns c, float y, float h) {
        float alpha = row.runsHere() ? 1f : DIMMED_ALPHA;
        iconTile(draw, row, c.icon(), y + (h - fs() * ICON_EM) * 0.5f, fs() * ICON_EM, alpha);
        ImFont nameFont = ui.fonts().smallMedium();
        ImFont lineFont = ui.fonts().caption();
        float block = nameFont.getFontSize() * LINE_HEIGHT + lineFont.getFontSize() * TIGHT_LINE;
        float top = y + (h - block) * 0.5f;
        nameLine(draw, row, c, top, alpha);
        String second = c.isCompact() ? compactLine(row) : row.summary();
        ui.text(draw, lineFont, c.name(), top + nameFont.getFontSize() * LINE_HEIGHT, ImGuiTheme.COL_FG2,
                ui.ellipsize(lineFont, second, c.nameW()));
        if (!c.isCompact()) {
            priceBadge(draw, row.pricing(), c.price(), y + (h - w.badgeHeight()) * 0.5f);
            stateLabel(draw, row, c.state(), y, h);
        }
    }

    /** The category icon on its tile; the tile turns accent once the script is on this PC. */
    void iconTile(ImDrawList draw, StoreRow row, float x, float y, float size, float alpha) {
        boolean isHere = row.state().isOnThisPc();
        int bg = isHere ? ImGuiTheme.COL_ACCENT_SOFT : ImGuiTheme.COL_ELEVATED;
        int fg = isHere ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG2;
        ui.iconTile(draw, x, y, size, CategoryStyle.icon(row.category()), Controls.scaleAlpha(fg, alpha),
                Controls.scaleAlpha(bg, alpha));
    }

    private void nameLine(ImDrawList draw, StoreRow row, Columns c, float y, float alpha) {
        ImFont nameFont = ui.fonts().smallMedium();
        ImFont byFont = ui.fonts().small();
        float yours = row.isOwned() ? w.badgeWidth("Yours") + ui.m().u(2) : 0f;
        String name = ui.ellipsize(nameFont, row.name(), c.nameW() - yours);
        ui.text(draw, nameFont, c.name(), y, Controls.scaleAlpha(ImGuiTheme.COL_FG, alpha), name);
        float x = c.name() + ui.width(nameFont, name) + ui.m().u(1.5f);
        float room = c.name() + c.nameW() - yours - x;
        if (room > 0f && !row.author().isBlank()) {
            String by = ui.ellipsize(byFont, row.author(), room);
            ui.text(draw, byFont, x, y, ImGuiTheme.COL_FG2, by);
            x += ui.width(byFont, by);
        }
        if (row.isOwned()) {
            float by = y + (nameFont.getFontSize() * LINE_HEIGHT - w.badgeHeight()) * 0.5f;
            w.badge(draw, x + ui.m().u(2), by, "Yours", ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT, 0);
        }
    }

    /** With no room for the price and state columns, the second line carries them. */
    private static String compactLine(StoreRow row) {
        String state = RowPresentation.state(row).text();
        String price = RowPresentation.price(row.pricing());
        return price.isEmpty() ? state : state + " · " + price;
    }

    /** Free and Paid badges; nothing when the catalogue did not say. */
    void priceBadge(ImDrawList draw, Pricing pricing, float x, float y) {
        switch (pricing) {
            case FREE -> w.badge(draw, x, y, "Free", ImGuiTheme.COL_FG, 0, ImGuiTheme.COL_BORDER);
            case PAID -> w.badge(draw, x, y, "Paid", ImGuiTheme.COL_FG, ImGuiTheme.COL_ELEVATED, ImGuiTheme.COL_BORDER);
            case UNKNOWN -> { }
        }
    }

    private void stateLabel(ImDrawList draw, StoreRow row, float x, float y, float h) {
        RowPresentation.StateLabel label = RowPresentation.state(row);
        ImFont mono = ui.fonts().monoCaption();
        int col = toneColour(label.tone());
        float tx = x;
        switch (label.tone()) {
            case OK -> {
                ui.textCentredY(draw, ui.fonts().caption(), tx, y, h, col, Icons.CHECK);
                tx += ui.width(ui.fonts().caption(), Icons.CHECK) + ui.m().u(1.5f);
            }
            case BUSY -> {
                w.spinner(draw, tx + ui.m().u(1), y + h * 0.5f, ui.m().u(1), col);
                tx += ui.m().u(2) + ui.m().u(1.5f);
            }
            case UPDATE, MUTED -> { }
        }
        ui.textCentredY(draw, mono, tx, y, h, col, label.text());
    }

    static int toneColour(RowPresentation.Tone tone) {
        return switch (tone) {
            case OK -> ImGuiTheme.COL_ACCENT;
            case UPDATE, BUSY -> ImGuiTheme.COL_INFO;
            case MUTED -> ImGuiTheme.COL_FG3;
        };
    }
}
