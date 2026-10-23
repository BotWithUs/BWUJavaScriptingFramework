package com.botwithus.bot.cli;

import com.botwithus.bot.cli.groups.ClientGroup;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** How a group's members line up with the connections the host has. */
public final class GroupMembers {

    private GroupMembers() {
    }

    /**
     * The members of {@code group} with no connection in {@code live}, by account
     * UUID, in member order. Unresolved members are not included: see
     * {@link ClientGroup#unresolved()}.
     */
    public static List<String> offline(ClientGroup group, List<Connection> live) {
        Set<String> online = live.stream()
                .map(Connection::getIdentifiedUuid)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        return group.members().stream().filter(uuid -> !online.contains(uuid)).toList();
    }
}
