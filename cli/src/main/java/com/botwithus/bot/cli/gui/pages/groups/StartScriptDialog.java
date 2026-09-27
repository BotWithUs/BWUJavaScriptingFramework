package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Start script dialog: pick an installed script, read what starting it on
 * the group will do, member by member, then start it. Search has the keyboard
 * on open; ↑↓ move, Enter starts, Esc closes.
 */
final class StartScriptDialog {

    private static final String POPUP_ID = "##group-start-script";
    private static final float WIDTH_EM = 41.33f;
    private static final float HEIGHT_EM = 40f;
    private static final float ROW_EM = 2.933f;
    private static final float TILE_EM = 1.733f;
    private static final float LINE = 1.4f;
    /** How far down an empty list its message sits, as a fraction of the list. */
    private static final float EMPTY_TEXT_TOP = 0.3f;
    private static final int QUERY_CAPACITY = 128;
    /** How much of an option row a long script name may take before the version. */
    private static final float NAME_SHARE = 0.7f;
    private static final List<Segment> CHOICES = List.of(Segment.of("Also start"), Segment.of("Stop it and switch"));

    private final GroupWidgets w;
    private final GroupsModel model;
    private final ImString query = new ImString(QUERY_CAPACITY);
    private GroupId group;
    private int highlighted;
    private BusyChoice choice = BusyChoice.SWITCH;
    private boolean isOpening;

    StartScriptDialog(GroupWidgets widgets, GroupsModel model) {
        this.w = widgets;
        this.model = model;
    }

    void open(GroupId id) {
        group = id;
        query.set("");
        highlighted = 0;
        choice = BusyChoice.SWITCH;
        isOpening = true;
    }

    /** Package-private: the dev preview's seam for highlighting a script and choosing, as a user would. */
    void choose(int row, BusyChoice busyChoice) {
        highlighted = row;
        choice = busyChoice;
    }

    void render(GroupsSnapshot snapshot, List<ScriptEntry> catalog) {
        boolean isFirst = isOpening;
        isOpening = false;
        if (!w.beginModal(POPUP_ID, WIDTH_EM, HEIGHT_EM, isFirst)) {
            return;
        }
        Optional<ClientGroup> shown = group == null ? Optional.empty() : snapshot.group(group);
        boolean isClosing = shown.isEmpty() || content(shown.get(), snapshot, catalog, isFirst);
        if (isClosing) {
            GroupWidgets.closeModal();
            group = null;
        }
        GroupWidgets.endModal();
    }

    /** Draws the dialog; returns true when it should close. */
    private boolean content(ClientGroup shown, GroupsSnapshot snapshot, List<ScriptEntry> catalog, boolean isFirst) {
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float width = ImGui.getWindowWidth();
        float height = ImGui.getWindowHeight();
        List<ScriptEntry> visible = filtered(catalog);
        highlighted = Math.max(0, Math.min(highlighted, visible.size() - 1));
        Optional<ScriptEntry> current = visible.isEmpty() ? Optional.empty() : Optional.of(visible.get(highlighted));
        Optional<StartPlan> plan = current.map(e -> StartPlan.of(shown, snapshot, e.info().name(), choice));
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            return true;
        }
        boolean isStartKey = keys(visible.size());
        float headerH = header(shown, x, y, width, isFirst);
        float footerH = w.m().u(3) * 2f + w.m().controlHeight();
        float planH = plan.map(p -> planHeight(p, width)).orElse(0f);
        boolean isStartRow = list(catalog.isEmpty(), visible, x, y + headerH, width, height - headerH - footerH - planH);
        plan.ifPresent(p -> planBlock(p, x, y + height - footerH - planH, width));
        Footer footer = footer(current, plan, x, y + height - footerH, width);
        boolean isStart = (isStartKey || isStartRow || footer == Footer.START) && plan.filter(StartPlan::canStart)
                .isPresent();
        if (isStart) {
            model.startScript(shown.id(), plan.get().script(), choice);
        }
        return isStart || footer == Footer.CANCEL || group == null;
    }

    private enum Footer { NONE, START, CANCEL }

    private List<ScriptEntry> filtered(List<ScriptEntry> catalog) {
        String q = query.get().strip().toLowerCase(Locale.ROOT);
        return catalog.stream().filter(entry -> q.isEmpty() || matches(entry.info(), q)).toList();
    }

    private static boolean matches(ScriptInfo info, String q) {
        return (info.name() + " " + info.categoryLabel() + " " + info.description()).toLowerCase(Locale.ROOT)
                .contains(q);
    }

    /** ↑↓ move the highlight; returns true when Enter was pressed. */
    private boolean keys(int count) {
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
            highlighted = Math.min(count - 1, highlighted + 1);
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
            highlighted = Math.max(0, highlighted - 1);
        }
        return ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false);
    }

    private float header(ClientGroup shown, float x, float y, float width, boolean isFirst) {
        ImGuiTheme.Metrics m = w.m();
        float left = x + m.u(4);
        float inner = width - m.u(4) * 2f;
        if (w.modalTitle("##start-close", "Start a script", " on " + shown.name(), left, y + m.u(4), inner)) {
            group = null;
        }
        float searchY = y + m.u(4) + m.controlHeight() + m.u(3);
        ImGui.setCursorScreenPos(left, searchY);
        w.ui().searchBox("##start-search", query, "Search installed scripts", inner, m.controlHeight(), isFirst);
        return searchY - y + m.controlHeight() + m.u(3);
    }

    /** The scripts, one row each, in a region that scrolls; returns true on a double click. */
    private boolean list(boolean isCatalogEmpty, List<ScriptEntry> visible, float x, float y, float width,
                         float height) {
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        ImGui.setCursorScreenPos(x, y + 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.beginChild("##start-list", width, Math.max(1f, height - 1f), false, ImGuiWindowFlags.None);
        boolean isDouble = false;
        if (visible.isEmpty()) {
            emptyList(isCatalogEmpty, width, height);
        }
        for (int i = 0; i < visible.size(); i++) {
            isDouble |= option(visible.get(i), i, width);
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
        return isDouble;
    }

    private void emptyList(boolean isCatalogEmpty, float width, float height) {
        Controls ui = w.ui();
        String text = isCatalogEmpty
                ? "No scripts installed yet. Put a script JAR in the scripts folder and it shows up here."
                : "No installed scripts match.";
        float cx = ImGui.getCursorScreenPosX() + width * 0.5f;
        ui.centredParagraph(ImGui.getWindowDrawList(), ui.fonts().small(), cx,
                ImGui.getCursorScreenPosY() + height * EMPTY_TEXT_TOP, width - w.m().u(8) * 2f,
                ui.fonts().small().getFontSize() * LINE, ImGuiTheme.COL_FG2, text);
    }

    private boolean option(ScriptEntry entry, int index, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = w.fs() * ROW_EM;
        boolean isClicked = ImGui.invisibleButton("##start-opt-" + index, width, h);
        boolean isDouble = isClicked && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left);
        if (isClicked) {
            highlighted = index;
        }
        boolean isOn = index == highlighted;
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isOn || ImGui.isItemHovered()) {
            draw.addRectFilled(x, y, x + width, y + h, isOn ? ImGuiTheme.COL_BG : ImGuiTheme.COL_SURFACE);
        }
        draw.addLine(x, y + h - 0.5f, x + width, y + h - 0.5f, ImGuiTheme.COL_BORDER);
        float tile = w.fs() * TILE_EM;
        ScriptInfo info = entry.info();
        ui.iconTile(draw, x + m.u(4), y + (h - tile) * 0.5f, tile, CategoryStyle.icon(info.category()),
                isOn ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG2,
                isOn ? ImGuiTheme.COL_ACCENT_SOFT : ImGuiTheme.COL_SURFACE);
        optionText(draw, info, x + m.u(4) + tile + m.u(3), y, width - tile - m.u(4) * 2f - m.u(3), h);
        return isDouble;
    }

    private void optionText(ImDrawList draw, ScriptInfo info, float x, float y, float width, float h) {
        Controls ui = w.ui();
        ImFont name = ui.fonts().smallMedium();
        ImFont desc = ui.fonts().caption();
        float nameH = name.getFontSize() * LINE;
        float top = y + (h - nameH - desc.getFontSize() * LINE) * 0.5f;
        String shown = ui.ellipsize(name, info.name(), width * NAME_SHARE);
        ui.textCentredY(draw, name, x, top, nameH, ImGuiTheme.COL_FG, shown);
        if (!info.version().isBlank()) {
            ui.textCentredY(draw, ui.fonts().monoCaption(), x + ui.width(name, shown) + w.m().u(1.5f), top, nameH,
                    ImGuiTheme.COL_FG2, "v" + info.version());
        }
        String description = info.description().isBlank() ? info.categoryLabel() : info.description();
        ui.text(draw, desc, x, top + nameH, ImGuiTheme.COL_FG2, ui.ellipsize(desc, description, width));
    }

    // ── What happens ──────────────────────────────────────────────────────

    private float planHeight(StartPlan plan, float width) {
        ImGuiTheme.Metrics m = w.m();
        ImFont font = w.ui().fonts().caption();
        float lineH = font.getFontSize() * LINE;
        float textW = textWidth(width);
        float h = m.u(3) * 2f + font.getFontSize() + m.u(2);
        for (PlanLines.Line line : PlanLines.of(plan)) {
            h += w.ui().wrap(font, line.text(), textW).size() * lineH + m.u(1);
            if (line.kind() == PlanLines.Kind.BUSY) {
                h += busyControlsHeight(textW);
            }
        }
        return h;
    }

    private float textWidth(float width) {
        ImGuiTheme.Metrics m = w.m();
        return width - m.u(4) * 2f - w.ui().width(w.ui().fonts().caption(), Icons.PLAY) - m.u(2);
    }

    private float busyControlsHeight(float textW) {
        ImFont font = w.ui().fonts().caption();
        int noteLines = Math.max(w.ui().wrap(font, PlanLines.ALSO_START_NOTE, textW).size(),
                w.ui().wrap(font, PlanLines.SWITCH_NOTE, textW).size());
        return w.m().u(1) + w.m().controlSmallHeight() + w.m().u(1) + noteLines * font.getFontSize() * LINE;
    }

    private void planBlock(StartPlan plan, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + planHeight(plan, width), ImGuiTheme.COL_SURFACE);
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        ImFont font = ui.fonts().caption();
        float left = x + m.u(4);
        float cy = y + m.u(3);
        ui.text(draw, ui.fonts().captionMedium(), left, cy, ImGuiTheme.COL_FG3, "WHAT HAPPENS");
        cy += font.getFontSize() + m.u(2);
        float textX = left + ui.width(font, Icons.PLAY) + m.u(2);
        float textW = textWidth(width);
        for (PlanLines.Line line : PlanLines.of(plan)) {
            cy = planLine(draw, line, left, textX, cy, textW) + m.u(1);
            if (line.kind() == PlanLines.Kind.BUSY) {
                cy = busyControls(textX, cy, textW);
            }
        }
    }

    /** One line, its lead in the foreground colour; returns the y under it. */
    private float planLine(ImDrawList draw, PlanLines.Line line, float iconX, float x, float y, float width) {
        Controls ui = w.ui();
        ImFont font = ui.fonts().caption();
        float lineH = font.getFontSize() * LINE;
        ui.text(draw, font, iconX, y + (lineH - font.getFontSize()) * 0.5f, line.iconColor(), line.icon());
        List<String> wrapped = ui.wrap(font, line.text(), width);
        float ly = y;
        for (String text : wrapped) {
            ui.text(draw, font, x, ly, ImGuiTheme.COL_FG2, text);
            ly += lineH;
        }
        if (!wrapped.isEmpty() && wrapped.getFirst().startsWith(line.lead())) {
            ui.text(draw, font, x, y, ImGuiTheme.COL_FG, line.lead());
        }
        return ly;
    }

    /** The busy members' switch and what it will do; returns the y under them. */
    private float busyControls(float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        float cy = y + m.u(1);
        ImGui.setCursorScreenPos(x, cy);
        int picked = ui.segmented("##start-busy", CHOICES, choice == BusyChoice.ALSO_START ? 0 : 1, 0f,
                m.controlSmallHeight());
        if (picked >= 0) {
            choice = picked == 0 ? BusyChoice.ALSO_START : BusyChoice.SWITCH;
        }
        cy += m.controlSmallHeight() + m.u(1);
        ImFont font = ui.fonts().caption();
        for (String line : ui.wrap(font, PlanLines.note(choice), width)) {
            ui.text(ImGui.getWindowDrawList(), font, x, cy, ImGuiTheme.COL_FG2, line);
            cy += font.getFontSize() * LINE;
        }
        return y + busyControlsHeight(width);
    }

    // ── Footer ────────────────────────────────────────────────────────────

    private Footer footer(Optional<ScriptEntry> current, Optional<StartPlan> plan, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        float h = m.controlHeight();
        float by = y + m.u(3);
        hints(draw, x + m.u(4), by, h);
        String label = startLabel(current, plan);
        float startW = ui.buttonWidth(Icons.PLAY, label, Tone.PRIMARY);
        float cancelW = ui.buttonWidth(null, "Cancel", Tone.GHOST);
        float right = x + width - m.u(4);
        ImGui.setCursorScreenPos(right - startW - m.u(2) - cancelW, by);
        if (ui.button("##start-cancel", null, "Cancel", Tone.GHOST, true)) {
            return Footer.CANCEL;
        }
        ImGui.setCursorScreenPos(right - startW, by);
        boolean canStart = plan.filter(StartPlan::canStart).isPresent();
        return ui.button("##start-go", Icons.PLAY, label, Tone.PRIMARY, canStart) ? Footer.START : Footer.NONE;
    }

    private void hints(ImDrawList draw, float x, float y, float h) {
        Controls ui = w.ui();
        ImFont font = ui.fonts().caption();
        float ky = y + (h - ui.kbdHeight()) * 0.5f;
        ui.kbd(draw, x, ky, "↑↓");
        float cx = x + ui.kbdWidth("↑↓") + w.m().u(1.5f);
        ui.textCentredY(draw, font, cx, y, h, ImGuiTheme.COL_FG2, "move ·");
        cx += ui.width(font, "move ·") + w.m().u(1.5f);
        ui.kbd(draw, cx, ky, "Enter");
        cx += ui.kbdWidth("Enter") + w.m().u(1.5f);
        ui.textCentredY(draw, font, cx, y, h, ImGuiTheme.COL_FG2, "start");
    }

    /** "Start Woodcutting on 3", or "Queue Woodcutting for 2" when nothing starts now. */
    static String startLabel(Optional<ScriptEntry> current, Optional<StartPlan> plan) {
        if (current.isEmpty() || plan.isEmpty()) {
            return "Start";
        }
        String name = current.get().info().name();
        int now = plan.get().startsNow().size();
        if (now == 0 && !plan.get().queued().isEmpty()) {
            return "Queue " + name + " for " + plan.get().queued().size();
        }
        return "Start " + name + " on " + now;
    }
}
