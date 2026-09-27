package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Hand-built groups and members for the Groups page tests. */
final class GroupFixtures {

    static final String OAK = "3f9a1c2e00000000000000000000aaaa";
    static final String HOLLOW = "e4410b7a00000000000000000000bbbb";
    static final String WREN = "6b1e8c0400000000000000000000cccc";
    static final String DUSK = "4e92d71a00000000000000000000dddd";
    static final String FERN = "b71d09e400000000000000000000eeee";
    static final String BRACKEN = "0a6d2f5800000000000000000000ffff";
    static final String WOODCUTTING = "Woodcutting";
    static final String DIVINATION = "Divination";
    static final int WORLD = 84;

    private GroupFixtures() {
    }

    static ClientGroup group(String name, String... members) {
        GroupId id = new GroupId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        return new ClientGroup(id, name, Optional.empty(), Arrays.asList(members), List.of(), Optional.empty());
    }

    static ClientGroup withUnresolved(ClientGroup group, String... pipes) {
        return new ClientGroup(group.id(), group.name(), group.description(), group.members(),
                Arrays.asList(pipes), group.manager());
    }

    static ClientGroup managed(ClientGroup group, String script, boolean shouldRun) {
        return group.withManager(Optional.of(new ManagerSlot(script, shouldRun)));
    }

    static MemberFacts connected(String uuid, String name, ScriptFact... scripts) {
        return new MemberFacts(uuid, Optional.of(name), OptionalInt.of(WORLD), MemberLink.CONNECTED,
                Arrays.asList(scripts));
    }

    static MemberFacts linked(String uuid, String name, MemberLink link, ScriptFact... scripts) {
        return new MemberFacts(uuid, Optional.of(name), OptionalInt.of(WORLD), link, Arrays.asList(scripts));
    }

    static ScriptFact fact(String name, ScriptState state) {
        return ScriptFact.of(name, state);
    }

    static GroupsSnapshot snapshot(List<ClientGroup> groups, MemberFacts... members) {
        Map<String, MemberFacts> byUuid = new LinkedHashMap<>();
        for (MemberFacts facts : members) {
            byUuid.put(facts.uuid(), facts);
        }
        List<PickableClient> clients = byUuid.values().stream()
                .map(facts -> new PickableClient(ClientKey.account(facts.uuid()), facts.name(), facts.world(),
                        facts.primary().map(ScriptFact::name), Optional.empty()))
                .toList();
        return new GroupsSnapshot(groups, byUuid, clients);
    }
}
