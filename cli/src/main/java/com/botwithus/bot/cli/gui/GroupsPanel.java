package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.ConnectionGroup;

import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Groups management panel — create/delete groups, add/remove connections to groups.
 */
public class GroupsPanel implements GuiPanel {

    private final ImString newGroupName = new ImString("", 128);

    @Override
    public String title() {
        return "Groups";
    }

    @Override
    public void render(CliContext ctx) {
        // Create group controls
        GuiHelpers.textSecondary("Create Group:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##newGroupName", newGroupName);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonPrimary(Icons.PLUS + "  Create")) {
            String name = newGroupName.get().trim();
            if (!name.isEmpty()) {
                ctx.createGroup(name);
                newGroupName.set("");
            }
        }

        // Groups list
        Map<String, ConnectionGroup> groups = ctx.getGroups();
        if (groups.isEmpty()) {
            ImGui.spacing();
            GuiHelpers.textMuted("No groups created. Use the form above to create one.");
            return;
        }

        GuiHelpers.sectionHeader("Groups");

        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("groupsTable", 4, flags)) {
            ImGui.tableSetupColumn("Group Name", 0, 1.0f);
            ImGui.tableSetupColumn("Members", 0, 0.5f);
            ImGui.tableSetupColumn("Add Connection", 0, 1.5f);
            ImGui.tableSetupColumn("Actions", 0, 0.6f);
            ImGui.tableHeadersRow();

            int groupIdx = 0;
            for (var entry : groups.entrySet()) {
                String groupName = entry.getKey();
                ConnectionGroup group = entry.getValue();
                Set<String> members = group.getConnectionNames();

                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                ImGui.text(groupName);

                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(members.size()));

                ImGui.tableSetColumnIndex(2);
                renderAddConnectionDropdown(ctx, groupName, group, groupIdx);

                ImGui.tableSetColumnIndex(3);
                ImGui.pushID("grp_del_" + groupIdx);
                if (GuiHelpers.smallButtonDanger(Icons.TRASH + " Delete")) {
                    ctx.deleteGroup(groupName);
                }
                ImGui.popID();

                groupIdx++;
            }

            ImGui.endTable();
        }

        // Member details per group
        GuiHelpers.sectionHeader("Group Members");

        int grpIdx = 0;
        for (var entry : groups.entrySet()) {
            String groupName = entry.getKey();
            ConnectionGroup group = entry.getValue();
            Set<String> members = group.getConnectionNames();

            int treeFlags = ImGuiTreeNodeFlags.DefaultOpen;
            if (ImGui.treeNodeEx("grp_tree_" + grpIdx, treeFlags, groupName + " (" + members.size() + " members)")) {
                if (members.isEmpty()) {
                    ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f,
                            "  No members");
                } else {
                    int memberIdx = 0;
                    for (String memberName : members) {
                        ImGui.text("  " + memberName);

                        // Check if connection is alive
                        boolean alive = false;
                        for (Connection conn : ctx.getConnections()) {
                            if (conn.getName().equals(memberName) && conn.isAlive()) {
                                alive = true;
                                break;
                            }
                        }
                        ImGui.sameLine();
                        if (alive) {
                            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, "(connected)");
                        } else {
                            ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f, "(disconnected)");
                        }

                        ImGui.sameLine(0, 12);
                        ImGui.pushID("member_rm_" + grpIdx + "_" + memberIdx);
                        if (ImGui.smallButton("Remove")) {
                            ctx.removeFromGroup(groupName, memberName);
                        }
                        ImGui.popID();
                        memberIdx++;
                    }
                }
                ImGui.treePop();
            }
            grpIdx++;
        }
    }

    private void renderAddConnectionDropdown(CliContext ctx, String groupName, ConnectionGroup group, int groupIdx) {
        var connections = new ArrayList<>(ctx.getConnections());
        // Filter out connections already in the group
        var available = connections.stream()
                .filter(c -> !group.contains(c.getName()))
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
            ctx.addToGroup(groupName, names[selected.get()]);
        }
        ImGui.popID();
    }
}
