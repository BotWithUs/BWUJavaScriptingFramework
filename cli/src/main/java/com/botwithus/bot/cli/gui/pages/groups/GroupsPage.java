package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Groups: the list of groups on the left and the selected group on the right,
 * with its actions, summary, manager slot and members; plus the Start script,
 * Add clients and New group dialogs. Groups follow the account, so a client that
 * restarts stays in its groups.
 */
public final class GroupsPage implements Page {

    private static final float LIST_WIDTH_EM = 17.6f;
    private static final float EMPTY_ICON_EM = 3.733f;
    private static final float EMPTY_TEXT_EM = 28f;
    private static final float LINE = 1.45f;

    private final GroupWidgets w;
    private final GroupsModel model;
    private final GroupsPageState state = new GroupsPageState();
    private final GroupListPane list;
    private final GroupHeader header;
    private final GroupOverview overview;
    private final MembersHeader membersHeader;
    private final MembersTable table;
    private final StartScriptDialog startDialog;
    private final PickClientsDialog pickDialog;
    private final AssignManagerDialog assignDialog;

    public GroupsPage(Controls ui, GroupsModel model) {
        this.w = new GroupWidgets(ui);
        this.model = model;
        this.list = new GroupListPane(w);
        this.startDialog = new StartScriptDialog(w, model);
        this.pickDialog = new PickClientsDialog(w, model);
        this.header = new GroupHeader(w, model, () -> state.selected().ifPresent(startDialog::open));
        this.assignDialog = new AssignManagerDialog(w, model);
        this.overview = new GroupOverview(w, new ManagerSlotView(w, model, assignDialog::open));
        Runnable openAdd = () -> state.selected().ifPresent(pickDialog::openAdd);
        this.membersHeader = new MembersHeader(w, model, openAdd);
        this.table = new MembersTable(w, model, openAdd);
    }

    @Override
    public PageId id() {
        return PageId.GROUPS;
    }

    @Override
    public Optional<NavBadge> badge() {
        int n = model.groupCount();
        return n > 0 ? Optional.of(NavBadge.count(n)) : Optional.empty();
    }

    /** Shows the group {@code id}, as a link from another page would. */
    public void show(GroupId id) {
        state.select(id);
    }

    @Override
    public void render() {
        model.takeCreated().ifPresent(state::select);
        GroupsSnapshot snapshot = model.snapshot();
        Optional<ClientGroup> shown = state.resolve(snapshot.groups());
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        float height = ImGui.getContentRegionAvailY();
        float listW = Math.min(w.fs() * LIST_WIDTH_EM, width * 0.5f);
        list.render(GroupViews.list(snapshot), state.selected(), GroupViews.ungrouped(snapshot), listW, height,
                state::select, pickDialog::openNew);
        float dx = x + listW;
        if (shown.isPresent()) {
            detail(GroupViews.detail(shown.get(), snapshot), iconsFor(model.catalog()), dx, y, width - listW,
                    height);
        } else {
            empty(dx, y, width - listW, height);
        }
        startDialog.render(snapshot, model.catalog());
        pickDialog.render(snapshot);
        assignDialog.render(snapshot);
    }

    private void detail(GroupDetail detail, Function<String, String> icons, float x, float y, float width,
                        float height) {
        float cy = y + header.render(detail, state, x, y, width);
        cy += overview.render(detail, x, cy, width);
        Map<String, MemberManagement> links = memberLinks(detail);
        cy += membersHeader.render(detail, state, links, x, cy, width);
        table.render(detail, state, icons, links, x, cy, width, y + height - cy);
    }

    /** The robot link under each member whose script a management script targets on its own, by row key. */
    private Map<String, MemberManagement> memberLinks(GroupDetail detail) {
        Map<String, MemberManagement> links = new HashMap<>();
        for (MemberRow row : detail.rows()) {
            switch (row) {
                case MemberRow.Account account -> account.primary().flatMap(script ->
                        model.memberManagement(detail.group().id(), account.key(), script.name()))
                        .ifPresent(link -> links.put(account.key(), link));
                case MemberRow.Unresolved _ -> { }
            }
        }
        return links;
    }

    /** No groups at all: say what a group is for, and offer to make one. */
    private void empty(float x, float y, float width, float height) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        float icon = w.fs() * EMPTY_ICON_EM;
        float textW = Math.min(width - m.u(6) * 2f, w.fs() * EMPTY_TEXT_EM);
        ImFont body = ui.fonts().small();
        String text = "Put clients in a group, then start or stop a script on all of them at once. Groups"
                + " follow the account, so a client that restarts stays in its group.";
        int lines = ui.wrap(body, text, textW).size();
        ImFont title = ui.fonts().bodyMedium();
        float blockH = icon + m.u(3) + title.getFontSize() + m.u(3) + lines * body.getFontSize() * LINE
                + m.u(3) + m.controlHeight();
        float cx = x + width * 0.5f;
        float top = y + (height - blockH) * 0.5f;
        draw.addCircle(cx, top + icon * 0.5f, icon * 0.5f, ImGuiTheme.COL_BORDER);
        ImFont iconFont = ui.fonts().titleMedium();
        ui.text(draw, iconFont, cx - ui.width(iconFont, Icons.LAYER_GROUP) * 0.5f,
                top + (icon - iconFont.getFontSize()) * 0.5f, ImGuiTheme.COL_FG2, Icons.LAYER_GROUP);
        float ty = top + icon + m.u(3);
        String heading = "Run one script on many clients";
        ui.text(draw, title, cx - ui.width(title, heading) * 0.5f, ty, ImGuiTheme.COL_FG, heading);
        ty += title.getFontSize() + m.u(3);
        ty += ui.centredParagraph(draw, body, cx, ty, textW, body.getFontSize() * LINE, ImGuiTheme.COL_FG2, text);
        float bw = ui.buttonWidth(Icons.PLUS, "Create a group", Tone.PRIMARY);
        ImGui.setCursorScreenPos(cx - bw * 0.5f, ty + m.u(3));
        if (ui.button("##groups-create-first", Icons.PLUS, "Create a group", Tone.PRIMARY, true)) {
            pickDialog.openNew();
        }
    }

    /** Each installed script's category icon, by name; a generic one for a script not installed. */
    private static Function<String, String> iconsFor(List<ScriptEntry> catalog) {
        Map<String, String> icons = new HashMap<>();
        for (ScriptEntry entry : catalog) {
            icons.put(entry.info().name().toLowerCase(Locale.ROOT), CategoryStyle.icon(entry.info().category()));
        }
        return name -> icons.getOrDefault(name.toLowerCase(Locale.ROOT), Icons.CODE);
    }

    // ── Package-private seams for the dev preview ─────────────────────────

    StartScriptDialog startDialog() {
        return startDialog;
    }

    PickClientsDialog pickDialog() {
        return pickDialog;
    }

    AssignManagerDialog assignDialog() {
        return assignDialog;
    }

    GroupsPageState state() {
        return state;
    }
}
