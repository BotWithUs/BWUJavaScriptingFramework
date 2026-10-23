package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.List;
import java.util.Optional;

/**
 * The Add clients and New group dialogs: a list of known clients to tick, each
 * showing the groups it is already in, since a client can be in several. A
 * client that cannot join a group is listed greyed out with the reason. New
 * group asks for a name first.
 */
final class PickClientsDialog {

    /** The dialog's tick scope while it creates a group rather than adding to one. */
    private static final String NEW_GROUP = "new-group";
    private static final String POPUP_ID = "##group-pick-clients";
    private static final float WIDTH_EM = 41.33f;
    private static final float HEIGHT_EM = 36f;
    private static final float ROW_EM = 2.8f;
    private static final float LINE = 1.4f;
    /** How far down an empty list its message sits, as a fraction of the list. */
    private static final float EMPTY_TEXT_TOP = 0.3f;
    private static final float GROUPS_SHARE = 0.4f;
    private static final int TEXT_CAPACITY = 128;

    private final GroupWidgets w;
    private final GroupsModel model;
    private final TickedKeys<String> ticks = new TickedKeys<>();
    private final ImString query = new ImString(TEXT_CAPACITY);
    private final ImString name = new ImString(TEXT_CAPACITY);
    /** The group being added to; empty while creating one. */
    private Optional<GroupId> target = Optional.empty();
    private boolean isOpening;
    private boolean isCloseRequested;
    /** Whether this dialog opened the modal and has not closed it. */
    private boolean isShown;

    PickClientsDialog(GroupWidgets widgets, GroupsModel model) {
        this.w = widgets;
        this.model = model;
    }

    void openAdd(GroupId id) {
        open(Optional.of(id));
    }

    void openNew() {
        open(Optional.empty());
    }

    private void open(Optional<GroupId> to) {
        target = to;
        query.set("");
        name.set("");
        ticks.clear();
        isCloseRequested = false;
        isOpening = true;
        isShown = true;
    }

    /** Package-private: the dev preview's seam for naming the group and ticking clients, as a user would. */
    void fill(String groupName, List<ClientKey> tick) {
        name.set(groupName);
        tick.forEach(key -> ticks.toggle(scope(), key.value()));
    }

    void render(GroupsSnapshot snapshot) {
        boolean isFirst = isOpening;
        isOpening = false;
        if (!w.beginModal(POPUP_ID, WIDTH_EM, HEIGHT_EM, isFirst)) {
            return;
        }
        Optional<ClientGroup> group = target.flatMap(snapshot::group);
        // ImGui can still hold the modal open when nothing here opened it (this
        // page was rebuilt while it was up): close it rather than guess what it was for.
        boolean isStray = !isShown;
        boolean isGone = target.isPresent() && group.isEmpty();
        if (isStray || isGone || content(group, snapshot, isFirst)) {
            GroupWidgets.closeModal();
            isShown = false;
        }
        GroupWidgets.endModal();
    }

    private String scope() {
        return target.map(GroupId::toString).orElse(NEW_GROUP);
    }

    /** Draws the dialog; returns true when it should close. */
    private boolean content(Optional<ClientGroup> group, GroupsSnapshot snapshot, boolean isFirst) {
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float width = ImGui.getWindowWidth();
        float height = ImGui.getWindowHeight();
        List<PickableClient> listed = GroupViews.candidates(snapshot, group, query.get());
        List<String> keys = listed.stream().filter(PickableClient::canJoin).map(c -> c.key().value()).toList();
        List<String> ticked = ticks.ticked(scope(), keys);
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            return true;
        }
        float headerH = header(group, x, y, width, isFirst);
        float footerH = w.m().u(3) * 2f + w.m().controlHeight();
        boolean isEmptyStore = snapshot.clients().isEmpty();
        list(listed, snapshot, group.isPresent(), isEmptyStore, x, y + headerH, width, height - headerH - footerH);
        boolean isDone = footer(group, listed, ticked, snapshot, x, y + height - footerH, width);
        return isDone || isCloseRequested;
    }

    private float header(Optional<ClientGroup> group, float x, float y, float width, boolean isFirst) {
        ImGuiTheme.Metrics m = w.m();
        float left = x + m.u(4);
        float inner = width - m.u(4) * 2f;
        String title = group.isPresent() ? "Add clients" : "New group";
        String suffix = group.map(g -> " to " + g.name()).orElse("");
        boolean isClosed = w.modalTitle("##pick-close", title, suffix, left, y + m.u(4), inner);
        isCloseRequested |= isClosed;
        float cy = y + m.u(4) + m.controlHeight() + m.u(3);
        if (group.isEmpty()) {
            ImGui.setCursorScreenPos(left, cy);
            if (isFirst) {
                ImGui.setKeyboardFocusHere();
            }
            w.ui().textField("##pick-name", name, "Group name, e.g. Yew team", false, inner);
            cy += m.controlHeight() + m.u(3);
        }
        ImGui.setCursorScreenPos(left, cy);
        w.ui().searchBox("##pick-search", query, "Filter clients", inner, m.controlHeight(),
                isFirst && group.isPresent());
        return cy - y + m.controlHeight() + m.u(3);
    }

    private void list(List<PickableClient> listed, GroupsSnapshot snapshot, boolean isAdding, boolean isEmptyStore,
                      float x, float y, float width, float height) {
        ImGui.getWindowDrawList().addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        ImGui.setCursorScreenPos(x, y + 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.beginChild("##pick-list", width, Math.max(1f, height - 1f), false, ImGuiWindowFlags.None);
        if (listed.isEmpty()) {
            empty(emptyText(isAdding, isEmptyStore), width, height);
        }
        for (PickableClient client : listed) {
            row(client, snapshot, width);
        }
        ImGui.endChild();
        ImGui.popStyleVar(2);
    }

    private String emptyText(boolean isAdding, boolean isEmptyStore) {
        if (isEmptyStore) {
            return "No clients yet. Clients show up here once the host has connected to them.";
        }
        if (!query.get().isBlank()) {
            return "No clients match.";
        }
        return isAdding ? "Every client is already in this group." : "No clients match.";
    }

    private void empty(String text, float width, float height) {
        Controls ui = w.ui();
        float cx = ImGui.getCursorScreenPosX() + width * 0.5f;
        ui.centredParagraph(ImGui.getWindowDrawList(), ui.fonts().small(), cx,
                ImGui.getCursorScreenPosY() + height * EMPTY_TEXT_TOP, width - w.m().u(8) * 2f,
                ui.fonts().small().getFontSize() * LINE, ImGuiTheme.COL_FG2, text);
    }

    private void row(PickableClient client, GroupsSnapshot snapshot, float width) {
        ImGuiTheme.Metrics m = w.m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = w.fs() * ROW_EM;
        String key = client.key().value();
        boolean canJoin = client.canJoin();
        boolean isTicked = ticks.isTicked(scope(), key);
        boolean isClicked = ImGui.invisibleButton("##pick-row-" + key, width, h);
        boolean isHovered = ImGui.isItemHovered();
        ImDrawList draw = ImGui.getWindowDrawList();
        if (canJoin && (isHovered || isTicked)) {
            draw.addRectFilled(x, y, x + width, y + h, isTicked ? GroupWidgets.ROW_SELECTED : ImGuiTheme.COL_SURFACE);
        }
        draw.addLine(x, y + h - 0.5f, x + width, y + h - 0.5f, ImGuiTheme.COL_BORDER);
        boolean isBoxClicked = w.checkbox("##pick-" + key, x + m.u(4), y, h, isTicked, canJoin);
        if (canJoin && (isClicked || isBoxClicked)) {
            ticks.toggle(scope(), key);
        }
        if (isHovered && !canJoin) {
            w.tooltip(client.refusal().orElse(""));
        }
        float box = w.ui().tickBoxSize();
        rowText(draw, client, snapshot, x + m.u(4) + box + m.u(3), y, width - m.u(4) * 2f - box - m.u(3), h);
        ImGui.setCursorScreenPos(x, y + h);
    }

    private void rowText(ImDrawList draw, PickableClient client, GroupsSnapshot snapshot, float x, float y,
                         float width, float h) {
        Controls ui = w.ui();
        ImFont nameFont = ui.fonts().small();
        ImFont mono = ui.fonts().monoCaption();
        float lineH = nameFont.getFontSize() * LINE;
        float top = y + (h - lineH - mono.getFontSize() * LINE) * 0.5f;
        float leftW = width * (1f - GROUPS_SHARE) - w.m().u(2);
        int nameCol = client.canJoin() ? ImGuiTheme.COL_FG : ImGuiTheme.COL_FG2;
        ui.textCentredY(draw, nameFont, x, top, lineH, nameCol,
                ui.ellipsize(nameFont, GroupText.nameOf(client), leftW));
        String id = GroupText.idOf(client.key()) + " · " + GroupText.world(client.world());
        ui.text(draw, mono, x, top + lineH, ImGuiTheme.COL_FG2, ui.ellipsize(mono, id, leftW));
        String right = rightText(client, snapshot);
        ImFont cap = ui.fonts().caption();
        String shown = ui.ellipsize(cap, right, width * GROUPS_SHARE);
        int col = client.canJoin() ? ImGuiTheme.COL_FG2 : ImGuiTheme.COL_WARN;
        ui.textCentredY(draw, cap, x + width - ui.width(cap, shown), y, h, col, shown);
    }

    /** The groups the client is in, else its script; for a client that cannot join, why. */
    static String rightText(PickableClient client, GroupsSnapshot snapshot) {
        if (!client.canJoin()) {
            return client.key().accountUuid().isEmpty() ? "No account UUID, cannot join"
                    : "Second client on this account, cannot join";
        }
        List<String> groups = client.accountUuid().map(snapshot::groupsOf).orElse(List.of()).stream()
                .map(ClientGroup::name).toList();
        if (!groups.isEmpty()) {
            return "in " + GroupText.join(groups);
        }
        return client.script().orElse("no script");
    }

    /** Cancel and Create / Add; returns true when the dialog should close. */
    private boolean footer(Optional<ClientGroup> group, List<PickableClient> listed, List<String> ticked,
                           GroupsSnapshot snapshot, float x, float y, float width) {
        ImGuiTheme.Metrics m = w.m();
        Controls ui = w.ui();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + width, y + 0.5f, ImGuiTheme.COL_BORDER);
        float h = m.controlHeight();
        float by = y + m.u(3);
        Optional<String> problem = group.isEmpty() ? nameProblem(snapshot) : Optional.empty();
        String note = problem.orElse(ticked.size() + " selected");
        // An empty name is the next step, not a mistake: only a taken name is drawn as a warning.
        boolean isWarning = problem.isPresent() && !name.get().isBlank();
        ui.textCentredY(draw, ui.fonts().caption(), x + m.u(4), by, h,
                isWarning ? ImGuiTheme.COL_WARN : ImGuiTheme.COL_FG2, note);
        String label = okLabel(group.isPresent(), ticked.size());
        String icon = group.isPresent() ? GroupWidgets.USER_PLUS : Icons.PLUS;
        float okW = ui.buttonWidth(icon, label, Tone.PRIMARY);
        float cancelW = ui.buttonWidth(null, "Cancel", Tone.GHOST);
        float right = x + width - m.u(4);
        ImGui.setCursorScreenPos(right - okW - m.u(2) - cancelW, by);
        if (ui.button("##pick-cancel", null, "Cancel", Tone.GHOST, true)) {
            return true;
        }
        ImGui.setCursorScreenPos(right - okW, by);
        boolean canOk = group.isPresent() ? !ticked.isEmpty() : problem.isEmpty();
        boolean isEnter = canOk && group.isEmpty() && ImGui.isKeyPressed(ImGuiKey.Enter, false);
        if (ui.button("##pick-ok", icon, label, Tone.PRIMARY, canOk) || isEnter) {
            List<ClientKey> picked = listed.stream().filter(c -> ticked.contains(c.key().value()))
                    .map(PickableClient::key).toList();
            group.ifPresentOrElse(g -> model.addMembers(g.id(), picked),
                    () -> model.createGroup(name.get(), picked));
            return true;
        }
        return false;
    }

    /** Why the name typed cannot make a group, said as it is typed; empty when it can. */
    private Optional<String> nameProblem(GroupsSnapshot snapshot) {
        String wanted = name.get().strip();
        if (wanted.isEmpty()) {
            return Optional.of("Name the group to create it.");
        }
        boolean isTaken = snapshot.groups().stream()
                .anyMatch(g -> g.name().equals(wanted));
        return isTaken ? Optional.of("There is already a group called " + wanted + ".") : Optional.empty();
    }

    /** "Create group with 2", "Add 1 client". */
    static String okLabel(boolean isAdding, int ticked) {
        if (isAdding) {
            return "Add " + GroupText.count(ticked, "client");
        }
        return ticked == 0 ? "Create group" : "Create group with " + ticked;
    }
}
