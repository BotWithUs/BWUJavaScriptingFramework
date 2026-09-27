package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.MemberChange;

import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.List;
import java.util.Optional;

/**
 * Groups management panel — create/delete groups, add/remove clients. Members
 * are accounts; a client that is not connected stays in its groups.
 */
public class GroupsPanel implements GuiPanel {

    public GroupsPanel() {}

    private final ImString newGroupName = new ImString("", 128);

    @Override
    public String title() {
        return "Groups";
    }

    @Override
    public void render(CliContext ctx) {
        renderCreateGroupControls(ctx);

        // Groups list
        List<ClientGroup> groups = ctx.getGroupStore().all();
        if (groups.isEmpty()) {
            ImGui.spacing();
            GuiHelpers.textSecondary("No groups created. Use the form above to create one.");
            return;
        }

        GuiHelpers.sectionHeader("Groups");
        renderGroupsTable(ctx, groups);

        // Member details per group
        GuiHelpers.sectionHeader("Group Members");
        renderGroupMembers(ctx, groups);
    }

    private void renderCreateGroupControls(CliContext ctx) {
        GuiHelpers.textSecondary("Create Group:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##newGroupName", newGroupName);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonPrimary(Icons.PLUS + "  Create")) {
            String name = newGroupName.get().trim();
            if (!name.isEmpty()) {
                ctx.getGroupStore().create(name, Optional.empty());
                newGroupName.set("");
            }
        }
    }

    private void renderGroupsTable(CliContext ctx, List<ClientGroup> groups) {
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("groupsTable", 4, flags)) {
            ImGui.tableSetupColumn("Group Name", 0, 1.0f);
            ImGui.tableSetupColumn("Members", 0, 0.5f);
            ImGui.tableSetupColumn("Add Connection", 0, 1.5f);
            ImGui.tableSetupColumn("Actions", 0, 0.6f);
            ImGui.tableHeadersRow();

            int groupIdx = 0;
            for (ClientGroup group : groups) {
                renderGroupRow(ctx, group, groupIdx);
                groupIdx++;
            }

            ImGui.endTable();
        }
    }

    private void renderGroupRow(CliContext ctx, ClientGroup group, int groupIdx) {
        ImGui.tableNextRow();

        ImGui.tableSetColumnIndex(0);
        ImGui.text(group.name());

        ImGui.tableSetColumnIndex(1);
        ImGui.text(String.valueOf(group.members().size() + group.unresolved().size()));

        ImGui.tableSetColumnIndex(2);
        renderAddConnectionDropdown(ctx, group, groupIdx);

        ImGui.tableSetColumnIndex(3);
        ImGui.pushID("grp_del_" + groupIdx);
        if (GuiHelpers.smallButtonDanger(Icons.TRASH + " Delete")) {
            ctx.getGroupStore().delete(group.id());
        }
        ImGui.popID();
    }

    private void renderGroupMembers(CliContext ctx, List<ClientGroup> groups) {
        int grpIdx = 0;
        for (ClientGroup group : groups) {
            renderGroupMemberTree(ctx, group, grpIdx);
            grpIdx++;
        }
    }

    private void renderGroupMemberTree(CliContext ctx, ClientGroup group, int grpIdx) {
        int count = group.members().size() + group.unresolved().size();
        int treeFlags = ImGuiTreeNodeFlags.DefaultOpen;
        if (ImGui.treeNodeEx("grp_tree_" + grpIdx, treeFlags, group.name() + " (" + count + " members)")) {
            if (count == 0) {
                ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                        "  No members");
            }
            int memberIdx = 0;
            for (String uuid : group.members()) {
                renderMemberRow(ctx, group, uuid, grpIdx, memberIdx);
                memberIdx++;
            }
            for (String pipe : group.unresolved()) {
                renderUnresolvedRow(ctx.getGroupStore(), group, pipe, grpIdx, memberIdx);
                memberIdx++;
            }
            ImGui.treePop();
        }
    }

    private void renderUnresolvedRow(GroupStore store, ClientGroup group, String pipe, int grpIdx, int memberIdx) {
        ImGui.text("  unknown client (pipe " + pipe + ")");
        ImGui.sameLine(0, 12);
        ImGui.pushID("member_rm_" + grpIdx + "_" + memberIdx);
        if (ImGui.smallButton("Remove")) {
            store.removeUnresolved(group.id(), pipe);
        }
        ImGui.popID();
    }

    private void renderMemberRow(CliContext ctx, ClientGroup group, String uuid, int grpIdx, int memberIdx) {
        ImGui.text("  " + ctx.describeAccount(uuid));

        boolean alive = !ctx.liveConnectionsOf(uuid).isEmpty();
        ImGui.sameLine();
        if (alive) {
            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "(connected)");
        } else {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "(disconnected)");
        }

        ImGui.sameLine(0, 12);
        ImGui.pushID("member_rm_" + grpIdx + "_" + memberIdx);
        if (ImGui.smallButton("Remove")) {
            ctx.getGroupStore().removeMember(group.id(), uuid);
        }
        ImGui.popID();
    }

    private void renderAddConnectionDropdown(CliContext ctx, ClientGroup group, int groupIdx) {
        // Only a connected client on a real account can join, and only once.
        var available = ctx.getConnections().stream()
                .filter(c -> canJoin(ctx.clientKeyOf(c.getName()), group))
                .toList();

        if (available.isEmpty()) {
            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "No connections to add");
            return;
        }

        String[] names = available.stream().map(Connection::getName).toArray(String[]::new);
        ImGui.pushID("grp_add_" + groupIdx);
        ImGui.pushItemWidth(120);
        ImInt selected = new ImInt(0);
        ImGui.combo("##addConn", selected, names);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton("Add")) {
            ctx.getGroupStore().addMember(group.id(), ctx.clientKeyOf(names[selected.get()]));
        }
        ImGui.popID();
    }

    private static boolean canJoin(ClientKey key, ClientGroup group) {
        return MemberChange.refusalFor(key).isEmpty()
                && key.accountUuid().filter(group::contains).isEmpty();
    }
}
