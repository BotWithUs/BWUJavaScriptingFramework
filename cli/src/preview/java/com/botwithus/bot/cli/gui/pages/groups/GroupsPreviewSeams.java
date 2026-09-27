package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.pages.groups.GroupsPageState.Confirm;

import java.util.List;

/**
 * The dev preview's reach into the Groups page, standing in for the clicks a
 * user would make: pick a group, tick members, open a dialog and fill it in,
 * ask to stop everything, start a rename. Lives in the preview source set only;
 * nothing here ships.
 */
public final class GroupsPreviewSeams {

    private GroupsPreviewSeams() {}

    public static void select(GroupsPage page, GroupId id) {
        page.show(id);
    }

    public static void tick(GroupsPage page, GroupId id, String rowKey) {
        page.state().ticks().toggle(id, rowKey);
    }

    public static void openStartScript(GroupsPage page, GroupId id) {
        page.startDialog().open(id);
    }

    /** Highlights catalogue row {@code row} in the open Start script dialog and picks {@code choice}. */
    public static void choose(GroupsPage page, int row, BusyChoice choice) {
        page.startDialog().choose(row, choice);
    }

    public static void openAddClients(GroupsPage page, GroupId id) {
        page.pickDialog().openAdd(id);
    }

    public static void openNewGroup(GroupsPage page) {
        page.pickDialog().openNew();
    }

    /** Types {@code name} and ticks {@code clients} in the open Add clients or New group dialog. */
    public static void fill(GroupsPage page, String name, List<ClientKey> clients) {
        page.pickDialog().fill(name, clients);
    }

    /** Opens the Assign manager dialog on {@code id} with row {@code row} highlighted. */
    public static void openAssignManager(GroupsPage page, GroupId id, int row) {
        page.assignDialog().open(id);
        page.assignDialog().highlight(row);
    }

    public static void askStopAll(GroupsPage page) {
        page.state().ask(Confirm.STOP_ALL);
    }

    public static void askDelete(GroupsPage page) {
        page.state().ask(Confirm.DELETE);
    }

    public static void startRename(GroupsPage page, String current) {
        page.state().startRename(current);
    }
}
