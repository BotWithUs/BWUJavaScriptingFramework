package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the Groups page draws from, read once per frame: the groups, what
 * the host knows about each member, and every client it could add.
 *
 * @param groups  in the order they were created
 * @param members by account UUID; a member missing here is {@linkplain MemberFacts#unknown unknown}
 * @param clients every client the host knows, live or remembered, in the order it first saw them
 */
public record GroupsSnapshot(List<ClientGroup> groups, Map<String, MemberFacts> members,
                             List<PickableClient> clients) {

    /** Nothing at all: no groups, no clients. */
    public static final GroupsSnapshot EMPTY = new GroupsSnapshot(List.of(), Map.of(), List.of());

    public GroupsSnapshot {
        groups = List.copyOf(groups);
        members = Map.copyOf(members);
        clients = List.copyOf(clients);
    }

    /** What is known about the client on account {@code uuid}. */
    public MemberFacts facts(String uuid) {
        return members.getOrDefault(uuid, MemberFacts.unknown(uuid));
    }

    public Optional<ClientGroup> group(GroupId id) {
        return groups.stream().filter(group -> group.id().equals(id)).findFirst();
    }

    /** The groups the client on account {@code uuid} is in. */
    public List<ClientGroup> groupsOf(String uuid) {
        return groups.stream().filter(group -> group.contains(uuid)).toList();
    }
}
