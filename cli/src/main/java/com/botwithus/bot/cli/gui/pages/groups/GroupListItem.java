package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.GroupId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One group in the page's left-hand list.
 *
 * @param dots    one per member, in member order, then one per unresolved member
 * @param running how many members are running a script
 * @param size    how many members, unresolved ones included
 * @param manager the name of the management script assigned to the group, if any
 */
public record GroupListItem(GroupId id, String name, List<MemberHealth> dots, int running, int size,
                            Optional<String> manager) {

    public GroupListItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(manager, "manager");
        dots = List.copyOf(dots);
    }

    /** "3 of 4 running", or "empty" for a group with no members. */
    public String runningText() {
        return size == 0 ? "empty" : running + " of " + size + " running";
    }
}
