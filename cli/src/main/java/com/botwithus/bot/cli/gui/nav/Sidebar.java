package com.botwithus.bot.cli.gui.nav;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Motion;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.List;
import java.util.Optional;

/**
 * The Advanced sidebar: the Clients and On this PC sections stacked from the
 * top, then a flexible gap, then the Online block in its own outlined box and
 * Settings, both pinned to the bottom. Clicking an item selects its page in the
 * {@link PageRegistry}.
 *
 * <p>Every size is a ratio of the body font, like the rest of the token kit, so
 * the sidebar scales with DPI along with the text.</p>
 */
public final class Sidebar {

    /**
     * 188 px in the prototype; a little wider here because the kit has no 11 px
     * mono, and {@code scripts/management/} in the 12 px one needs the room.
     */
    private static final float WIDTH_EM = 13.2f;
    /** A two-line item: label plus folder path or sign-in state. */
    private static final float TALL_ITEM_EM = 2.8f;
    private static final float ICON_COLUMN_EM = 1.067f;
    private static final float LABEL_LINE_HEIGHT = 1.3f;
    private static final float SECOND_LINE_HEIGHT = 1.35f;
    private static final float HEADING_ICON_GAP_EM = 0.4f;
    private static final float PILL_PAD_X_EM = 0.4f;
    private static final float PILL_PAD_Y_EM = 0.2f;
    private static final float TOOLTIP_WRAP_EM = 20f;
    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;

    private final Controls ui;

    public Sidebar(Controls ui) {
        this.ui = ui;
    }

    public float width() {
        return ui.m().fontSize() * WIDTH_EM;
    }

    /** Draws the sidebar at the cursor, {@code height} tall; a click selects that item's page. */
    public void render(PageRegistry registry, float height) {
        ImGuiTheme.Metrics m = ui.m();
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(2), m.u(3));
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.beginChild("##sidebar", width(), height, false, ImGuiWindowFlags.AlwaysUseWindowPadding);
        ImGui.popStyleColor();

        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.hairline() * 0.5f;
        float top = ImGui.getWindowPosY();
        ImGui.getWindowDrawList().addLine(right, top, right, top + ImGui.getWindowHeight(),
                ImGuiTheme.COL_BORDER, m.hairline());

        List<NavSection> sections = registry.sections();
        boolean isFirst = true;
        for (NavSection s : sections) {
            if (!s.isPinnedToBottom()) {
                renderSection(registry, s, isFirst);
                isFirst = false;
            }
        }
        float bottom = bottomHeight(registry, sections);
        ImGui.dummy(0f, Math.max(m.u(4), ImGui.getContentRegionAvailY() - bottom));
        for (NavSection s : sections) {
            if (s.isPinnedToBottom()) {
                renderPinned(registry, s);
            }
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    // ── Layout ─────────────────────────────────────────────────────────────

    private void renderSection(PageRegistry registry, NavSection section, boolean isFirst) {
        if (section.hasHeading()) {
            ImGuiTheme.Metrics m = ui.m();
            heading(section, isFirst ? m.u(1) : m.u(4), m.u(2), m.u(2));
        }
        items(registry, section, ImGui.getContentRegionAvailX(), false);
    }

    private void renderPinned(PageRegistry registry, NavSection section) {
        ImGuiTheme.Metrics m = ui.m();
        if (!section.isBoxed()) {
            ImGui.dummy(0f, m.u(2));
            items(registry, section, ImGui.getContentRegionAvailX(), false);
            return;
        }
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float h = boxHeight(registry, section);
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_BG, m.radiusLarge());
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusLarge());
        float pad = m.u(1);
        ImGui.setCursorScreenPos(x + pad, y + pad);
        ImGui.beginGroup();
        heading(section, m.u(2), m.u(1), m.u(2));
        items(registry, section, w - pad * 2f, true);
        ImGui.endGroup();
        ImGui.setCursorScreenPos(x, y + h);
        ImGui.dummy(w, 0f);
    }

    private void items(PageRegistry registry, NavSection section, float width, boolean isBoxed) {
        for (Page page : registry.pagesIn(section)) {
            if (item(page, page.id() == registry.selected(), width, isBoxed)) {
                registry.select(page.id());
            }
        }
    }

    // ── Heights (the bottom block is measured first so it can be pinned) ──

    private float bottomHeight(PageRegistry registry, List<NavSection> sections) {
        ImGuiTheme.Metrics m = ui.m();
        float h = 0f;
        for (NavSection s : sections) {
            if (s.isPinnedToBottom()) {
                h += s.isBoxed() ? boxHeight(registry, s) : m.u(2) + itemsHeight(registry, s);
            }
        }
        return h;
    }

    private float boxHeight(PageRegistry registry, NavSection section) {
        ImGuiTheme.Metrics m = ui.m();
        return m.u(1) * 2f + headingHeight(m.u(2), m.u(1)) + itemsHeight(registry, section);
    }

    private float itemsHeight(PageRegistry registry, NavSection section) {
        float h = 0f;
        for (Page page : registry.pagesIn(section)) {
            h += itemHeight(page.secondLine().isPresent());
        }
        return h;
    }

    private float itemHeight(boolean hasSecondLine) {
        return hasSecondLine ? ui.m().fontSize() * TALL_ITEM_EM : ui.m().controlHeight();
    }

    private float headingHeight(float padTop, float padBottom) {
        return padTop + ui.fonts().captionMedium().getFontSize() + padBottom;
    }

    // ── Heading ────────────────────────────────────────────────────────────

    private void heading(NavSection section, float padTop, float padBottom, float padX) {
        float x = ImGui.getCursorScreenPosX() + padX;
        float y = ImGui.getCursorScreenPosY() + padTop;
        ImDrawList draw = ImGui.getWindowDrawList();
        if (!section.icon().isEmpty()) {
            ImFont iconFont = ui.fonts().caption();
            ui.text(draw, iconFont, x, y, ImGuiTheme.COL_FG3, section.icon());
            x += ui.width(iconFont, section.icon()) + ui.m().fontSize() * HEADING_ICON_GAP_EM;
        }
        ui.text(draw, ui.fonts().captionMedium(), x, y, ImGuiTheme.COL_FG3, section.heading().toUpperCase());
        ImGui.dummy(0f, headingHeight(padTop, padBottom));
    }

    // ── Item ───────────────────────────────────────────────────────────────

    /** One item at the cursor; returns true when it was clicked. */
    private boolean item(Page page, boolean isSelected, float width, boolean isBoxed) {
        Optional<SecondLine> second = page.secondLine();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = itemHeight(second.isPresent());
        boolean clicked = ImGui.invisibleButton("##nav-" + page.id().name(), width, h);
        boolean hovered = ImGui.isItemHovered();
        float t = isSelected ? 1f : Motion.step("nav:" + page.id().name(), hovered ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        ImGuiTheme.Metrics m = ui.m();
        draw.addRectFilled(x, y, x + width, y + h, Controls.scaleAlpha(ImGuiTheme.COL_ELEVATED, t), m.radius());

        int fg = Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        float iconW = m.fontSize() * ICON_COLUMN_EM;
        ImFont iconFont = ui.fonts().small();
        float iconX = x + m.u(2) + (iconW - ui.width(iconFont, page.icon())) * 0.5f;
        ui.textCentredY(draw, iconFont, iconX, y, h, isSelected ? ImGuiTheme.COL_ACCENT : fg, page.icon());

        float labelX = x + m.u(2) + iconW + m.u(2);
        float right = x + width - m.u(2);
        Optional<NavBadge> badge = page.badge();
        if (badge.isPresent()) {
            right = drawBadge(draw, badge.get(), right, y, h, isBoxed) - m.u(2);
        }
        boolean isTruncated = drawLabels(draw, page, second, labelX, right - labelX, y, h, fg, t);
        if (hovered) {
            tooltip(page, second, isTruncated);
        }
        return clicked;
    }

    /** Draws the label and the optional second line; returns true when either was shortened. */
    private boolean drawLabels(ImDrawList draw, Page page, Optional<SecondLine> second,
                               float x, float maxW, float y, float h, int fg, float t) {
        ImFont labelFont = ui.fonts().smallMedium();
        String label = ui.ellipsize(labelFont, page.id().label(), maxW);
        if (second.isEmpty()) {
            ui.textCentredY(draw, labelFont, x, y, h, fg, label);
            return !label.equals(page.id().label());
        }
        ImFont secondFont = secondFont(second.get());
        float labelH = labelFont.getFontSize() * LABEL_LINE_HEIGHT;
        float block = labelH + secondFont.getFontSize() * SECOND_LINE_HEIGHT;
        float top = y + (h - block) * 0.5f;
        ui.text(draw, labelFont, x, top, fg, label);
        int dim = Controls.lerp(ImGuiTheme.COL_FG3, ImGuiTheme.COL_FG2, t);
        boolean isShortened = drawSecondLine(draw, second.get(), secondFont, x, top + labelH, maxW, dim);
        return isShortened || !label.equals(page.id().label());
    }

    private boolean drawSecondLine(ImDrawList draw, SecondLine line, ImFont font, float x, float y,
                                   float maxW, int col) {
        float lead = switch (line) {
            case SecondLine.FolderPath ignored -> 0f;
            case SecondLine.AccountStatus account -> drawLiveDot(draw, account.isLive(), x, y, font);
        };
        String shown = ui.ellipsize(font, line.text(), maxW - lead);
        ui.text(draw, font, x + lead, y, col, shown);
        return !shown.equals(line.text());
    }

    /** The sign-in dot before the store's second line; returns the width it takes. */
    private float drawLiveDot(ImDrawList draw, boolean isLive, float x, float y, ImFont font) {
        float r = ui.m().dot() * 0.5f;
        int col = isLive ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG3;
        draw.addCircleFilled(x + r, y + font.getFontSize() * 0.5f, r, col);
        return ui.m().dot() + ui.m().u(1);
    }

    private ImFont secondFont(SecondLine line) {
        return switch (line) {
            case SecondLine.FolderPath ignored -> ui.fonts().monoCaption();
            case SecondLine.AccountStatus ignored -> ui.fonts().caption();
        };
    }

    /** Draws the badge right-aligned to {@code right}; returns its left edge. */
    private float drawBadge(ImDrawList draw, NavBadge badge, float right, float y, float h, boolean isPill) {
        ImFont font = ui.fonts().monoCaption();
        float textW = ui.width(font, badge.text());
        int col = badge.isWarning() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG2;
        if (!isPill) {
            ui.textCentredY(draw, font, right - textW, y, h, col, badge.text());
            return right - textW;
        }
        float fs = ui.m().fontSize();
        float w = textW + fs * PILL_PAD_X_EM * 2f;
        float ph = font.getFontSize() + fs * PILL_PAD_Y_EM * 2f;
        float py = y + (h - ph) * 0.5f;
        draw.addRectFilled(right - w, py, right, py + ph, ImGuiTheme.COL_ELEVATED, ph * 0.5f);
        draw.addRect(right - w + 0.5f, py + 0.5f, right - 0.5f, py + ph - 0.5f, ImGuiTheme.COL_BORDER, ph * 0.5f);
        ui.textCentredY(draw, font, right - w + fs * PILL_PAD_X_EM, py, ph,
                badge.isWarning() ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG, badge.text());
        return right - w;
    }

    private void tooltip(Page page, Optional<SecondLine> second, boolean isTruncated) {
        String hint = page.id().hint();
        String full = isTruncated ? second.map(SecondLine::text).orElse(page.id().label()) : "";
        if ((hint.isEmpty() && full.isEmpty()) || !ImGui.isItemHovered(ImGuiHoveredFlags.DelayNormal)) {
            return;
        }
        ImGui.beginTooltip();
        ImGui.pushFont(ui.fonts().small());
        ImGui.pushTextWrapPos(ui.m().fontSize() * TOOLTIP_WRAP_EM);
        if (!full.isEmpty()) {
            ImGui.textUnformatted(full);
        }
        if (!hint.isEmpty()) {
            ImGui.textUnformatted(hint);
        }
        ImGui.popTextWrapPos();
        ImGui.popFont();
        ImGui.endTooltip();
    }
}
