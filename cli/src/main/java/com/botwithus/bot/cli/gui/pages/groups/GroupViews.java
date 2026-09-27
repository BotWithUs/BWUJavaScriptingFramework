package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.ManagerSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Turns a {@link GroupsSnapshot} into what the Groups page draws. Pure: the
 * live page and the dev preview both draw through it, so what the preview shows
 * is what the host would show for the same facts.
 */
public final class GroupViews {

    private GroupViews() {
    }

    /** The left-hand list: one item per group, in the order they were created. */
    public static List<GroupListItem> list(GroupsSnapshot snapshot) {
        return snapshot.groups().stream().map(group -> item(group, snapshot)).toList();
    }

    /** One group in the list: a dot per member, unresolved members drawn as closed. */
    public static GroupListItem item(ClientGroup group, GroupsSnapshot snapshot) {
        List<MemberHealth> dots = new ArrayList<>();
        for (String uuid : group.members()) {
            dots.add(snapshot.facts(uuid).health());
        }
        group.unresolved().forEach(pipe -> dots.add(MemberHealth.CLOSED));
        int running = (int) dots.stream().filter(dot -> dot == MemberHealth.RUNNING).count();
        return new GroupListItem(group.id(), group.name(), dots, running, dots.size(),
                group.manager().map(ManagerSlot::script));
    }

    /** The selected group: its summary and one row per member. */
    public static GroupDetail detail(ClientGroup group, GroupsSnapshot snapshot) {
        List<MemberRow> rows = new ArrayList<>();
        for (String uuid : group.members()) {
            rows.add(row(group, snapshot.facts(uuid), snapshot));
        }
        group.unresolved().forEach(pipe -> rows.add(new MemberRow.Unresolved(pipe)));
        return new GroupDetail(group, summary(group, snapshot), rows);
    }

    /** The four figures under the group's name, and what Stop all would touch. */
    public static GroupSummary summary(ClientGroup group, GroupsSnapshot snapshot) {
        List<MemberFacts> members = group.members().stream().map(snapshot::facts).toList();
        int running = countHealth(members, MemberHealth.RUNNING);
        int needsLook = (int) members.stream().filter(facts -> facts.health().needsLook()).count();
        Map<String, Integer> shown = scriptsShown(members);
        Optional<String> main = shown.entrySet().stream()
                .reduce((best, next) -> next.getValue() > best.getValue() ? next : best)
                .map(Map.Entry::getKey);
        int connected = (int) members.stream().filter(MemberFacts::isConnected).count();
        int stoppable = members.stream().filter(MemberFacts::isConnected).mapToInt(f -> f.active().size()).sum();
        int queued = members.stream().mapToInt(facts -> facts.queued().size()).sum();
        int size = members.size() + group.unresolved().size();
        return new GroupSummary(running, size, needsLook, main, Math.max(0, shown.size() - 1),
                averageLoop(members), connected, stoppable, queued);
    }

    /** How many known clients are in no group at all. */
    public static int ungrouped(GroupsSnapshot snapshot) {
        return (int) snapshot.clients().stream().filter(client -> !client.isInAny(snapshot.groups())).count();
    }

    /**
     * The clients the Add clients dialog lists for {@code target}, or the New
     * group dialog when it is empty: every known client not already in it whose
     * name or id contains {@code query}, ignoring case.
     */
    public static List<PickableClient> candidates(GroupsSnapshot snapshot, Optional<ClientGroup> target,
                                                  String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        return snapshot.clients().stream()
                .filter(client -> target.isEmpty() || !client.isInAny(List.of(target.get())))
                .filter(client -> q.isEmpty() || matches(client, q))
                .toList();
    }

    /** The one script action a member's row offers; see {@link RowAction}. */
    static RowAction actionFor(MemberFacts facts) {
        if (!facts.isConnected()) {
            return facts.queued().isEmpty() ? RowAction.NONE : RowAction.CANCEL_QUEUED;
        }
        if (!facts.active().isEmpty()) {
            return RowAction.STOP;
        }
        return facts.primary().map(fact -> switch (fact.state()) {
            case CRASHED -> RowAction.RESTART;
            case STOPPED -> RowAction.RUN;
            case QUEUED -> RowAction.CANCEL_QUEUED;
            case CUT_OFF, STALLED, RUNNING -> RowAction.NONE;
        }).orElse(RowAction.NONE);
    }

    private static MemberRow row(ClientGroup group, MemberFacts facts, GroupsSnapshot snapshot) {
        Optional<ScriptFact> primary = facts.primary();
        int more = facts.scripts().size() - (primary.isPresent() ? 1 : 0);
        List<String> otherGroups = snapshot.groupsOf(facts.uuid()).stream()
                .filter(other -> !other.id().equals(group.id()))
                .map(ClientGroup::name)
                .toList();
        return new MemberRow.Account(facts, GroupText.accountOf(facts), primary, more, otherGroups,
                actionFor(facts));
    }

    private static int countHealth(List<MemberFacts> members, MemberHealth health) {
        return (int) members.stream().filter(facts -> facts.health() == health).count();
    }

    /** How many members show each script first, in the order the scripts first appear. */
    private static Map<String, Integer> scriptsShown(List<MemberFacts> members) {
        Map<String, Integer> shown = new LinkedHashMap<>();
        for (MemberFacts facts : members) {
            facts.primary().ifPresent(fact -> shown.merge(fact.name(), 1, Integer::sum));
        }
        return shown;
    }

    private static OptionalDouble averageLoop(List<MemberFacts> members) {
        return members.stream()
                .filter(facts -> facts.health() == MemberHealth.RUNNING)
                .flatMap(facts -> facts.primary().stream())
                .filter(fact -> fact.avgLoopMs().isPresent())
                .mapToDouble(fact -> fact.avgLoopMs().getAsDouble())
                .average();
    }

    private static boolean matches(PickableClient client, String q) {
        return GroupText.nameOf(client).toLowerCase(Locale.ROOT).contains(q)
                || client.key().value().toLowerCase(Locale.ROOT).contains(q);
    }
}
