package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Draws one row of the members table and runs its buttons. */
final class MemberRowPainter {

    private static final float LINE = 1.35f;
    private static final float TILE_EM = 1.467f;
    private static final float TAG_PAD_EM = 0.4f;
    private static final float TAG_H_EM = 1.2f;
    private static final float SOFT_ALPHA = 0.12f;

    private final GroupWidgets w;
    private final GroupsModel model;

    MemberRowPainter(GroupWidgets widgets, GroupsModel model) {
        this.w = widgets;
        this.model = model;
    }

    /**
     * @param managed the robot link under the account, when a management script targets the
     *                member's script on its own
     */
    void paint(MemberRow row, GroupId group, TickedKeys<GroupId> ticks, MemberColumns cols,
               Function<String, String> icons, Optional<MemberManagement> managed, float x, float y, float width,
               float h) {
        boolean isTicked = ticks.isTicked(group, row.key());
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isTicked) {
            draw.addRectFilled(x + 1f, y + 1f, x + width - 1f, y + h, GroupWidgets.ROW_SELECTED);
        } else if (GroupWidgets.isHovering(x, y, width, h)) {
            draw.addRectFilled(x + 1f, y + 1f, x + width - 1f, y + h, GroupWidgets.ROW_HOVER);
        }
        if (w.checkbox("##tick-" + row.key(), cols.check(), y, h, isTicked, true)) {
            ticks.toggle(group, row.key());
        }
        switch (row) {
            case MemberRow.Account account -> account(account, group, cols, icons, managed, y, h);
            case MemberRow.Unresolved unresolved -> unresolved(unresolved, group, cols, y, h);
        }
    }

    private void account(MemberRow.Account row, GroupId group, MemberColumns cols, Function<String, String> icons,
                         Optional<MemberManagement> managed, float y, float h) {
        ImDrawList draw = ImGui.getWindowDrawList();
        String tag = row.otherGroups().isEmpty() ? "" : "+" + GroupText.count(row.otherGroups().size(), "group");
        twoLines(draw, cols.account(), y, h, cols.accountWidth(), row.account(), row.shortUuid(), tag);
        managed.ifPresent(link -> managedLink(draw, row, link, cols, y, h));
        if (!tag.isEmpty() && GroupWidgets.isHovering(cols.account(), y, cols.accountWidth(), h)) {
            w.tooltip("Also in " + GroupText.join(row.otherGroups()));
        }
        Controls ui = w.ui();
        if (cols.isWide()) {
            ui.textCentredY(draw, ui.fonts().monoCaption(), cols.world(), y, h, ImGuiTheme.COL_FG2,
                    GroupText.world(row.facts().world()));
            loop(draw, row, cols, y, h);
        }
        w.link(draw, cols.link(), y, h, row.facts().link());
        script(draw, row, cols, icons, y, h);
        actions(row, group, cols, y, h);
    }

    /**
     * The robot link after the account UUID: "🤖 Own settings", "🤖 Also
     * direct" or the other management script's name. It opens Management.
     */
    private void managedLink(ImDrawList draw, MemberRow.Account row, MemberManagement link, MemberColumns cols,
                             float y, float h) {
        Controls ui = w.ui();
        ImFont top = ui.fonts().small();
        ImFont mono = ui.fonts().monoCaption();
        ImFont cap = ui.fonts().caption();
        float topH = top.getFontSize() * LINE;
        float lineH = mono.getFontSize() * LINE;
        float lineY = y + (h - topH - lineH) * 0.5f + topH;
        float x = cols.account() + ui.width(mono, row.shortUuid());
        ui.textCentredY(draw, mono, x, lineY, lineH, ImGuiTheme.COL_FG2, " · ");
        float lx = x + ui.width(mono, " · ");
        float room = cols.account() + cols.accountWidth() - lx;
        float glyphW = ui.width(cap, Icons.ROBOT) + w.m().u(1);
        String label = ui.ellipsize(cap, link.label(), Math.max(0f, room - glyphW));
        float linkW = glyphW + ui.width(cap, label);
        ImGui.setCursorScreenPos(lx, lineY);
        boolean isClicked = ImGui.invisibleButton("##managed-" + row.key(), Math.max(1f, linkW), lineH);
        boolean isHovered = ImGui.isItemHovered();
        int col = isHovered ? ImGuiTheme.COL_FG : ImGuiTheme.COL_INFO;
        ui.textCentredY(draw, cap, lx, lineY, lineH, col, Icons.ROBOT);
        ui.textCentredY(draw, cap, lx + glyphW, lineY, lineH, col, label);
        if (isHovered) {
            w.tooltip(link.tooltip() + " Open Management.");
        }
        if (isClicked) {
            model.openManagement(link.managementScript());
        }
    }

    /** Name over a mono second line, with an optional bordered tag after the name. */
    private void twoLines(ImDrawList draw, float x, float y, float h, float width, String first, String second,
                          String tag) {
        Controls ui = w.ui();
        ImFont top = ui.fonts().small();
        ImFont bottom = ui.fonts().monoCaption();
        float topH = top.getFontSize() * LINE;
        float blockY = y + (h - topH - bottom.getFontSize() * LINE) * 0.5f;
        float tagW = tag.isEmpty() ? 0f : ui.width(ui.fonts().caption(), tag) + w.fs() * TAG_PAD_EM * 2f;
        String name = ui.ellipsize(top, first, Math.max(0f, width - tagW - w.m().u(1.5f)));
        ui.textCentredY(draw, top, x, blockY, topH, ImGuiTheme.COL_FG, name);
        if (!tag.isEmpty()) {
            float tx = x + ui.width(top, name) + w.m().u(1.5f);
            float th = w.fs() * TAG_H_EM;
            float ty = blockY + (topH - th) * 0.5f;
            draw.addRect(tx + 0.5f, ty + 0.5f, tx + tagW - 0.5f, ty + th - 0.5f, ImGuiTheme.COL_BORDER,
                    w.m().radiusSmall());
            ui.textCentredY(draw, ui.fonts().caption(), tx + w.fs() * TAG_PAD_EM, ty, th, ImGuiTheme.COL_FG2, tag);
        }
        ui.text(draw, bottom, x, blockY + topH, ImGuiTheme.COL_FG2, ui.ellipsize(bottom, second, width));
    }

    private void script(ImDrawList draw, MemberRow.Account row, MemberColumns cols, Function<String, String> icons,
                        float y, float h) {
        Controls ui = w.ui();
        float tile = w.fs() * TILE_EM;
        float tx = cols.script();
        float ty = y + (h - tile) * 0.5f;
        if (row.primary().isEmpty()) {
            ui.iconTile(draw, tx, ty, tile, GroupWidgets.MINUS, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED);
            ui.textCentredY(draw, ui.fonts().small(), tx + tile + w.m().u(2), y, h, ImGuiTheme.COL_FG2, "No script");
            return;
        }
        ScriptFact fact = row.primary().get();
        MemberLink link = row.facts().link();
        int tone = link.isConnected() ? toneOf(fact.state()) : ImGuiTheme.COL_FG2;
        ui.iconTile(draw, tx, ty, tile, icons.apply(fact.name()), tone, softOf(tone));
        String more = row.moreScripts() > 0 ? "  +" + row.moreScripts() : "";
        float textX = tx + tile + w.m().u(2);
        float textW = cols.scriptWidth() - tile - w.m().u(2);
        String state = GroupText.stateLine(fact, link);
        int stateCol = link.isConnected() || fact.state() == ScriptState.QUEUED ? stateColor(fact.state(), tone)
                : ImGuiTheme.COL_FG2;
        stacked(draw, textX, y, h, textW, fact.name() + more, state, stateCol);
    }

    /** Two lines, small over caption, the second in {@code col}. */
    private void stacked(ImDrawList draw, float x, float y, float h, float width, String first, String second,
                         int col) {
        Controls ui = w.ui();
        ImFont top = ui.fonts().small();
        ImFont bottom = ui.fonts().caption();
        float topH = top.getFontSize() * LINE;
        float blockY = y + (h - topH - bottom.getFontSize() * LINE) * 0.5f;
        ui.textCentredY(draw, top, x, blockY, topH, ImGuiTheme.COL_FG, ui.ellipsize(top, first, width));
        ui.text(draw, bottom, x, blockY + topH, col, ui.ellipsize(bottom, second, width));
    }

    private void loop(ImDrawList draw, MemberRow.Account row, MemberColumns cols, float y, float h) {
        Controls ui = w.ui();
        ImFont mono = ui.fonts().monoCaption();
        boolean isRunning = row.facts().health() == MemberHealth.RUNNING;
        String value = isRunning ? GroupText.loopMs(row.primary().orElseThrow().avgLoopMs()) : "—";
        String unit = isRunning ? " ms" : "";
        float right = cols.actions() - cols.gap();
        float unitW = ui.width(mono, unit);
        ui.textCentredY(draw, mono, right - unitW, y, h, ImGuiTheme.COL_FG3, unit);
        ui.textCentredY(draw, mono, right - unitW - ui.width(mono, value), y, h, ImGuiTheme.COL_FG, value);
    }

    private void actions(MemberRow.Account row, GroupId group, MemberColumns cols, float y, float h) {
        ImGuiTheme.Metrics m = w.m();
        float s = m.controlSmallHeight();
        float by = y + (h - s) * 0.5f;
        float bx = cols.end() - s;
        ImGui.setCursorScreenPos(bx, by);
        if (w.iconButton("##remove-" + row.key(), GroupWidgets.USER_MINUS, ImGuiTheme.COL_FG2,
                ImGuiTheme.COL_DANGER_SOFT, "Remove " + row.account() + " from this group")) {
            model.removeMembers(group, List.of(row.key()));
        }
        ImGui.setCursorScreenPos(bx - s - m.u(0.5f), by);
        scriptAction(row);
    }

    private void scriptAction(MemberRow.Account row) {
        String uuid = row.facts().uuid();
        String script = row.primary().map(ScriptFact::name).orElse("");
        String id = "##act-" + uuid;
        switch (row.action()) {
            case STOP -> {
                String what = row.facts().active().size() == 1 ? script
                        : GroupText.count(row.facts().active().size(), "script");
                if (w.iconButton(id, Icons.STOP, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_DANGER_SOFT,
                        "Stop " + what + " on " + row.account())) {
                    model.stopMembers(List.of(uuid));
                }
            }
            case CANCEL_QUEUED -> {
                if (w.iconButton(id, Icons.XMARK, ImGuiTheme.COL_FG2, ImGuiTheme.COL_ELEVATED,
                        "Cancel the start waiting for " + row.account())) {
                    model.stopMembers(List.of(uuid));
                }
            }
            case RUN -> {
                if (w.iconButton(id, Icons.PLAY, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                        "Run " + script + " on " + row.account())) {
                    model.run(uuid, script);
                }
            }
            case RESTART -> {
                if (w.iconButton(id, Icons.REDO, ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_SOFT,
                        "Restart " + script + " on " + row.account())) {
                    model.restart(uuid, script);
                }
            }
            case NONE -> { }
        }
    }

    private void unresolved(MemberRow.Unresolved row, GroupId group, MemberColumns cols, float y, float h) {
        ImDrawList draw = ImGui.getWindowDrawList();
        twoLines(draw, cols.account(), y, h, cols.accountWidth(), "Unknown client", "pipe " + row.pipe(), "");
        Controls ui = w.ui();
        ui.textCentredY(draw, ui.fonts().caption(), cols.link(), y, h, ImGuiTheme.COL_FG2, "Not identified yet");
        ui.textCentredY(draw, ui.fonts().caption(), cols.script(), y, h, ImGuiTheme.COL_FG3,
                ui.ellipsize(ui.fonts().caption(), "Joins by account once a client on this pipe is identified",
                        cols.scriptWidth()));
        float s = w.m().controlSmallHeight();
        ImGui.setCursorScreenPos(cols.end() - s, y + (h - s) * 0.5f);
        if (w.iconButton("##remove-" + row.key(), GroupWidgets.USER_MINUS, ImGuiTheme.COL_FG2,
                ImGuiTheme.COL_DANGER_SOFT, "Remove this unknown client from the group")) {
            model.removeMembers(group, List.of(row.key()));
        }
    }

    private static int toneOf(ScriptState state) {
        return switch (state) {
            case RUNNING -> ImGuiTheme.COL_ACCENT;
            case STALLED -> ImGuiTheme.COL_WARN;
            case CRASHED, CUT_OFF -> ImGuiTheme.COL_DANGER;
            case QUEUED -> ImGuiTheme.COL_INFO;
            case STOPPED -> ImGuiTheme.COL_FG2;
        };
    }

    private static int softOf(int tone) {
        return tone == ImGuiTheme.COL_FG2 ? ImGuiTheme.COL_ELEVATED : Controls.scaleAlpha(tone, SOFT_ALPHA);
    }

    private static int stateColor(ScriptState state, int tone) {
        return state == ScriptState.STOPPED ? ImGuiTheme.COL_FG2 : tone;
    }
}
