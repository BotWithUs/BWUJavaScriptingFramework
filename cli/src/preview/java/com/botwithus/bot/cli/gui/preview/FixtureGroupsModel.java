package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.cli.gui.pages.groups.BusyChoice;
import com.botwithus.bot.cli.gui.pages.groups.GroupsModel;
import com.botwithus.bot.cli.gui.pages.groups.GroupsSnapshot;
import com.botwithus.bot.cli.gui.pages.groups.ManagerChoice;
import com.botwithus.bot.cli.gui.pages.groups.ManagerInfo;
import com.botwithus.bot.cli.gui.pages.groups.MemberFacts;
import com.botwithus.bot.cli.gui.pages.groups.MemberLink;
import com.botwithus.bot.cli.gui.pages.groups.MemberManagement;
import com.botwithus.bot.cli.gui.pages.groups.Notice;
import com.botwithus.bot.cli.gui.pages.groups.PickableClient;
import com.botwithus.bot.cli.gui.pages.groups.ScriptFact;
import com.botwithus.bot.cli.gui.pages.groups.ScriptState;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * DEV ONLY. Groups for the preview: the design's three groups over eleven
 * clients in every member state, plus switches for the empty and edge states.
 * Actions do nothing; a scenario shows their result by switching fixtures.
 */
final class FixtureGroupsModel implements GroupsModel {

    static final GroupId WOODCUTTERS = idOf("Woodcutters");
    static final GroupId QUESTERS = idOf("Questers");
    static final GroupId DIV_TEAM = idOf("Div team");
    static final GroupId YEW_TEAM = idOf("Yew team");
    static final GroupId OLD_GROUP = idOf("Old farm");

    static final String OAKHEART = "3f9a1c2e5b7d4e10a2c4e6f8b0d2f4a6";
    static final String HOLLOWMERE = "e4410b7a9c3e4f21b5d7f9a1c3e5a7b9";
    static final String WRENFIELD = "6b1e8c04d2f64a32c6e8a0b2d4f6b8c0";
    static final String DUSKWATER = "4e92d71ae3a74b43d7f9b1c3e5a7c9d1";
    static final String TAMSIN = "90ce3f15f4b84c54e8a0c2d4f6b8dae2";
    static final String ASHGROVE = "c93f5a27a5c94d65f9b1d3e5a7c9ebf3";
    static final String FERNMOSS = "b71d09e4b6da4e76a0c2e4f6b8d0fca4";
    static final String BRACKEN = "0a6d2f58c7eb4f87b1d3f5a7c9e10db5";
    static final String QUILLON = "5c20aa91d8fc4098c2e4a6b8d0f21ec6";
    static final String KESTREL = "81c5e0b2e90d41a9d3f5b7c9e1a32fd7";
    static final String SABLETON = "f40a6c3dfa1e42bae4a6c8d0f2b430e8";
    static final String DEV_PIPE = "BotWithUs_20112";

    private static final String WOODCUTTING = "Woodcutting";
    private static final String DIVINATION = "Divination";
    private static final String BREAK_SCHEDULER = "Break Scheduler";
    private static final int W84 = 84;
    private static final int W58 = 58;
    private static final int W117 = 117;
    private static final int W31 = 31;
    private static final int W2 = 2;
    private static final int W44 = 44;
    private static final double OAK_MS = 142;
    private static final double WREN_MS = 118;
    private static final double ASH_MS = 211;
    private static final double KESTREL_MS = 74;

    private static final Duration MANAGER_UPTIME = Duration.ofMinutes(42).plusSeconds(10);
    private static final String LAST_ACTION = "14:05 stopScript · Woodcutting on Duskwater";
    private static final int OAKHEART_OWN = 2;
    private static final int FERNMOSS_OWN = 1;
    /**
     * Which member scripts Break Scheduler also targets on their own, with how
     * many values of its own each: the design's Oakheart, Wrenfield and Fernmoss.
     */
    private static final Map<String, Integer> DIRECT = Map.of(
            OAKHEART + "/" + WOODCUTTING, OAKHEART_OWN,
            WRENFIELD + "/" + WOODCUTTING, 0,
            FERNMOSS + "/" + DIVINATION, FERNMOSS_OWN);

    private final List<ScriptEntry> catalog;
    private GroupsSnapshot snapshot;
    private Optional<Notice> notice = Optional.empty();
    private ManagerInfo.State managerState = ManagerInfo.State.MANAGING;

    FixtureGroupsModel(List<ScriptEntry> catalog) {
        this.catalog = List.copyOf(catalog);
        this.snapshot = busySnapshot();
    }

    /** No groups at all. */
    void showNoGroups() {
        snapshot = new GroupsSnapshot(List.of(), snapshot.members(), snapshot.clients());
    }

    /** The busy groups plus an empty one. */
    void showEmptyGroup() {
        List<ClientGroup> groups = new ArrayList<>(snapshot.groups());
        groups.add(new ClientGroup(YEW_TEAM, "Yew team", Optional.of("Accounts moving on to yews next"),
                List.of(), List.of(), Optional.empty()));
        snapshot = new GroupsSnapshot(groups, snapshot.members(), snapshot.clients());
    }

    /**
     * A group carried over from before members were accounts, two of whose pipes
     * were never identified, and a notice saying one client could not be added.
     */
    void showUnresolved() {
        List<ClientGroup> groups = new ArrayList<>(snapshot.groups());
        groups.add(new ClientGroup(OLD_GROUP, "Old farm", Optional.of("Migrated from the pipe-keyed groups file"),
                List.of(KESTREL), List.of("BotWithUs_9120", "BotWithUs_14876"), Optional.empty()));
        snapshot = new GroupsSnapshot(groups, snapshot.members(), snapshot.clients());
        String reason = MemberChange.refusalFor(new ClientKey.Pipe(DEV_PIPE)).orElseThrow().reason();
        notice = Optional.of(Notice.problem("Could not add " + DEV_PIPE + ": " + reason));
    }

    /** Woodcutters' manager as Stop all leaves it: paused. */
    void showManagerPaused() {
        managerState = ManagerInfo.State.PAUSED;
    }

    @Override
    public GroupsSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public int groupCount() {
        return snapshot.groups().size();
    }

    @Override
    public List<ScriptEntry> catalog() {
        return catalog;
    }

    @Override
    public Optional<Notice> notice() {
        return notice;
    }

    @Override
    public void dismissNotice() {
        notice = Optional.empty();
    }

    @Override
    public Optional<GroupId> takeCreated() {
        return Optional.empty();
    }

    @Override
    public void createGroup(String name, List<ClientKey> members) { }

    @Override
    public void rename(GroupId id, String name) { }

    @Override
    public void delete(GroupId id) { }

    @Override
    public void addMembers(GroupId id, List<ClientKey> keys) { }

    @Override
    public void removeMembers(GroupId id, Collection<String> rowKeys) { }

    @Override
    public void startScript(GroupId id, String script, BusyChoice choice) { }

    @Override
    public void stopAll(GroupId id) { }

    @Override
    public void stopMembers(Collection<String> uuids) { }

    @Override
    public void run(String uuid, String script) { }

    @Override
    public void restart(String uuid, String script) { }

    @Override
    public Optional<ManagerInfo> manager(GroupId id) {
        boolean isManaging = managerState == ManagerInfo.State.MANAGING;
        return snapshot.group(id).flatMap(ClientGroup::manager).map(slot -> new ManagerInfo(slot.script(), "1.2",
                managerState, isManaging ? Optional.of(MANAGER_UPTIME) : Optional.empty(),
                Optional.of(LAST_ACTION), true));
    }

    @Override
    public List<ManagerChoice> managers() {
        return List.of(
                new ManagerChoice(BREAK_SCHEDULER, "1.2", "Staggers breaks so a group's members don't all play"
                        + " at once.", managedBy(BREAK_SCHEDULER)),
                new ManagerChoice("Restart on Crash", "1.0", "Restarts a client's script after a crash, at most"
                        + " three times an hour.", List.of()),
                new ManagerChoice("World Balancer", "0.2", "Moves members off worlds that get crowded.", List.of()));
    }

    private List<String> managedBy(String script) {
        return snapshot.groups().stream()
                .filter(g -> g.manager().filter(slot -> slot.script().equals(script)).isPresent())
                .map(ClientGroup::name)
                .toList();
    }

    @Override
    public void assignManager(GroupId id, String script, boolean isStartNow) { }

    @Override
    public void startManager(GroupId id) { }

    @Override
    public void openManagerSettings(GroupId id) { }

    /** Break Scheduler's direct targets, labelled by the host's own rules. */
    @Override
    public Optional<MemberManagement> memberManagement(GroupId id, String uuid, String script) {
        Integer own = DIRECT.get(uuid + "/" + script);
        if (own == null) {
            return Optional.empty();
        }
        Optional<String> groupManager = snapshot.group(id).flatMap(ClientGroup::manager).map(ManagerSlot::script);
        return Optional.of(MemberManagement.of(groupManager, BREAK_SCHEDULER, own, script));
    }

    @Override
    public void openManagement(String script) { }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private static GroupId idOf(String name) {
        return new GroupId(UUID.nameUUIDFromBytes(("preview/" + name).getBytes(StandardCharsets.UTF_8)));
    }

    private static GroupsSnapshot busySnapshot() {
        List<ClientGroup> groups = List.of(
                new ClientGroup(WOODCUTTERS, "Woodcutters", Optional.of("Yew accounts on world 84 and nearby"),
                        List.of(OAKHEART, HOLLOWMERE, WRENFIELD, DUSKWATER), List.of(),
                        Optional.of(new ManagerSlot(BREAK_SCHEDULER, true))),
                new ClientGroup(QUESTERS, "Questers",
                        Optional.of("Fresh accounts working through the starter quests"),
                        List.of(TAMSIN, ASHGROVE, QUILLON), List.of(), Optional.empty()),
                new ClientGroup(DIV_TEAM, "Div team", Optional.empty(), List.of(FERNMOSS, BRACKEN, OAKHEART),
                        List.of(), Optional.empty()));
        Map<String, MemberFacts> members = new LinkedHashMap<>();
        for (MemberFacts facts : members()) {
            members.put(facts.uuid(), facts);
        }
        List<PickableClient> clients = new ArrayList<>();
        for (MemberFacts facts : members.values()) {
            clients.add(new PickableClient(ClientKey.account(facts.uuid()), facts.name(), facts.world(),
                    facts.primary().map(ScriptFact::name), Optional.empty()));
        }
        ClientKey dev = new ClientKey.Pipe(DEV_PIPE);
        clients.add(new PickableClient(dev, Optional.empty(), OptionalInt.empty(), Optional.empty(),
                MemberChange.refusalFor(dev).map(MemberChange.Refused::reason)));
        return new GroupsSnapshot(groups, members, clients);
    }

    private static List<MemberFacts> members() {
        return List.of(
                live(OAKHEART, "Oakheart", W84, ScriptFact.running(WOODCUTTING, OAK_MS)),
                facts(HOLLOWMERE, "Hollowmere", W84, MemberLink.RECONNECTING,
                        ScriptFact.of(WOODCUTTING, ScriptState.STOPPED)),
                live(WRENFIELD, "Wrenfield", W58, ScriptFact.running(WOODCUTTING, WREN_MS)),
                live(DUSKWATER, "Duskwater", W117, ScriptFact.of(WOODCUTTING, ScriptState.STOPPED)),
                live(TAMSIN, "Tamsin Vale", W31, new ScriptFact("Cook's Assistant", ScriptState.CRASHED,
                        OptionalDouble.empty(), "NullPointerException in onLoop()")),
                live(ASHGROVE, "Ashgrove", W84, ScriptFact.running("The Restless Ghost", ASH_MS)),
                live(FERNMOSS, "Fernmoss", W2, new ScriptFact(DIVINATION, ScriptState.STALLED,
                        OptionalDouble.of(OAK_MS), "stuck in onLoop()")),
                facts(BRACKEN, "Brackenridge", W44, MemberLink.CLOSED, ScriptFact.of(DIVINATION, ScriptState.QUEUED)),
                live(QUILLON, "Quillon", W117),
                live(KESTREL, "Kestrel Moor", W2, ScriptFact.running("Walk to Flag", KESTREL_MS)),
                live(SABLETON, "Sableton", W44));
    }

    private static MemberFacts live(String uuid, String name, int world, ScriptFact... scripts) {
        return facts(uuid, name, world, MemberLink.CONNECTED, scripts);
    }

    private static MemberFacts facts(String uuid, String name, int world, MemberLink link, ScriptFact... scripts) {
        return new MemberFacts(uuid, Optional.of(name), OptionalInt.of(world), link, List.of(scripts));
    }
}
