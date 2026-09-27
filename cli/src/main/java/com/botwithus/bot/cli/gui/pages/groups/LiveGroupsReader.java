package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.AccountReply;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientLifecycle;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.cli.groups.QueuedStart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads a {@link GroupsSnapshot} from the live host: the group store, the client
 * registry, each member's connection and script runners, and the start-when-back
 * queue. Every source is safe to read on the render thread.
 */
final class LiveGroupsReader {

    private final CliContext ctx;

    LiveGroupsReader(CliContext ctx) {
        this.ctx = ctx;
    }

    GroupsSnapshot read() {
        List<ClientGroup> groups = ctx.getGroupStore().all();
        Map<ClientKey, ClientRecord> records = new LinkedHashMap<>();
        for (ClientRecord record : ctx.getClientRegistry().clients()) {
            records.put(record.key(), record);
        }
        Map<String, MemberFacts> members = new HashMap<>();
        for (ClientGroup group : groups) {
            for (String uuid : group.members()) {
                members.computeIfAbsent(uuid, u -> facts(u, records));
            }
        }
        List<PickableClient> clients = new ArrayList<>();
        for (ClientRecord record : records.values()) {
            clients.add(pickable(record, members, records));
        }
        return new GroupsSnapshot(groups, members, clients);
    }

    /** What is known about the client on account {@code uuid}, now. */
    MemberFacts facts(String uuid) {
        Map<ClientKey, ClientRecord> records = new HashMap<>();
        if (AccountReply.identified(uuid).isPresent()) {
            ctx.getClientRegistry().get(ClientKey.account(uuid)).ifPresent(r -> records.put(r.key(), r));
        }
        return facts(uuid, records);
    }

    /** The pipe of the live connection on account {@code uuid}, if it is connected. */
    Optional<String> pipeOf(String uuid) {
        return ctx.liveConnectionsOf(uuid).stream().map(Connection::getName).findFirst();
    }

    private MemberFacts facts(String uuid, Map<ClientKey, ClientRecord> records) {
        if (AccountReply.identified(uuid).isEmpty()) {
            return MemberFacts.unknown(uuid);
        }
        Optional<ClientRecord> record = Optional.ofNullable(records.get(ClientKey.account(uuid)));
        List<Connection> live = ctx.liveConnectionsOf(uuid);
        List<ScriptFact> scripts = new ArrayList<>(
                live.isEmpty() ? List.of() : RunnerFacts.of(live.getFirst().getRuntime().getRunners()));
        for (QueuedStart queued : ctx.getStartWhenBackQueue().forAccount(uuid)) {
            if (scripts.stream().noneMatch(fact -> fact.isNamed(queued.script()) && fact.state().isActive())) {
                scripts.add(ScriptFact.of(queued.script(), ScriptState.QUEUED));
            }
        }
        MemberLink link = live.isEmpty() ? record.map(r -> linkOf(r.lifecycle())).orElse(MemberLink.CLOSED)
                : MemberLink.CONNECTED;
        return new MemberFacts(uuid, record.flatMap(ClientRecord::name),
                record.map(ClientRecord::lastWorld).orElse(OptionalInt.empty()), link, scripts);
    }

    /** How a client with no live connection is linked: retrying, not retrying, or gone. */
    private static MemberLink linkOf(ClientLifecycle lifecycle) {
        return switch (lifecycle) {
            case ClientLifecycle.NotResponding waiting -> waiting.isRetrying()
                    ? MemberLink.RECONNECTING : MemberLink.NOT_RESPONDING;
            case ClientLifecycle.Identifying _, ClientLifecycle.Connected _, ClientLifecycle.Resuming _,
                 ClientLifecycle.Closed _ -> MemberLink.CLOSED;
        };
    }

    private PickableClient pickable(ClientRecord record, Map<String, MemberFacts> members,
                                    Map<ClientKey, ClientRecord> records) {
        ClientKey key = record.key();
        Optional<String> refusal = MemberChange.refusalFor(key).map(MemberChange.Refused::reason);
        Optional<String> script = refusal.isPresent() ? Optional.empty()
                : key.accountUuid()
                        .map(uuid -> members.computeIfAbsent(uuid, u -> facts(u, records)))
                        .flatMap(MemberFacts::primary)
                        .map(ScriptFact::name);
        return new PickableClient(key, record.name(), record.lastWorld(), script, refusal);
    }
}
