package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.BRACKEN;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.DIVINATION;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.DUSK;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.FERN;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.HOLLOW;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.OAK;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.WOODCUTTING;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.WREN;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.connected;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.fact;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.group;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.linked;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.managed;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.snapshot;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.withUnresolved;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The group list, the summary row and the members table, derived from what the host knows. */
class GroupViewsTest {

    private static final double OAK_LOOP = 140.0;
    private static final double WREN_LOOP = 120.0;
    private static final double AVG_LOOP = 130.0;
    private static final double TOLERANCE = 0.001;
    private static final String PIPE = "BotWithUs_4242";

    /** Six members, one in every state a list dot can show. */
    private static GroupsSnapshot everyState(ClientGroup group) {
        return snapshot(List.of(group),
                connected(OAK, "Oakheart", ScriptFact.running(WOODCUTTING, OAK_LOOP)),
                linked(HOLLOW, "Hollowmere", MemberLink.RECONNECTING, ScriptFact.running(WOODCUTTING, OAK_LOOP)),
                connected(WREN, "Wrenfield", ScriptFact.running(WOODCUTTING, WREN_LOOP)),
                connected(DUSK, "Duskwater", fact(WOODCUTTING, ScriptState.CRASHED)),
                connected(FERN, "Fernmoss", fact(DIVINATION, ScriptState.STALLED)),
                linked(BRACKEN, "Brackenridge", MemberLink.CLOSED));
    }

    private static final ClientGroup WOODCUTTERS = group("Woodcutters", OAK, HOLLOW, WREN, DUSK, FERN, BRACKEN);

    @Test
    void listItem_hasOneDotPerMemberByState_andCountsTheRunningOnes() {
        GroupListItem item = GroupViews.list(everyState(WOODCUTTERS)).getFirst();

        assertAll(
                () -> assertEquals(List.of(MemberHealth.RUNNING, MemberHealth.RECONNECTING, MemberHealth.RUNNING,
                        MemberHealth.CRASHED, MemberHealth.STALLED, MemberHealth.CLOSED), item.dots()),
                () -> assertEquals(2, item.running()),
                () -> assertEquals(6, item.size()),
                () -> assertEquals("2 of 6 running", item.runningText()),
                () -> assertEquals(Optional.empty(), item.manager()));
    }

    @Test
    void listItem_namesTheManager_whenOneIsAssigned() {
        ClientGroup group = managed(group("Questers", OAK), "Break Scheduler", true);

        GroupListItem item = GroupViews.list(snapshot(List.of(group), connected(OAK, "Oakheart"))).getFirst();

        assertEquals(Optional.of("Break Scheduler"), item.manager());
    }

    @Test
    void listItem_ofAnEmptyGroup_saysEmpty() {
        GroupListItem item = GroupViews.list(snapshot(List.of(group("Nobody")))).getFirst();

        assertAll(
                () -> assertEquals(List.of(), item.dots()),
                () -> assertEquals("empty", item.runningText()));
    }

    @Test
    void listItem_countsUnresolvedMembers_asClosedDots() {
        ClientGroup group = withUnresolved(group("Old", OAK), PIPE);

        GroupListItem item = GroupViews.list(snapshot(List.of(group), connected(OAK, "Oakheart"))).getFirst();

        assertAll(
                () -> assertEquals(List.of(MemberHealth.IDLE, MemberHealth.CLOSED), item.dots()),
                () -> assertEquals(2, item.size()));
    }

    @Test
    void summary_countsRunningAndThoseThatNeedALook_andAveragesTheRunningLoops() {
        GroupSummary summary = GroupViews.summary(WOODCUTTERS, everyState(WOODCUTTERS));

        assertAll(
                () -> assertEquals(2, summary.running()),
                () -> assertEquals(6, summary.size()),
                () -> assertEquals(2, summary.needsLook(), "the crashed and the stalled member"),
                () -> assertEquals(Optional.of(WOODCUTTING), summary.mainScript()),
                () -> assertEquals(1, summary.otherScripts()),
                () -> assertEquals(AVG_LOOP, summary.avgLoopMs().orElseThrow(), TOLERANCE),
                () -> assertEquals(4, summary.connected()),
                () -> assertEquals(3, summary.stoppable(), "two running and one stalled on connected members"));
    }

    @Test
    void summary_hasNoLoopTime_andNoMainScript_whenNothingRuns() {
        ClientGroup group = group("Idle", OAK);

        GroupSummary summary = GroupViews.summary(group, snapshot(List.of(group), connected(OAK, "Oakheart")));

        assertAll(
                () -> assertEquals(OptionalDouble.empty(), summary.avgLoopMs()),
                () -> assertEquals(Optional.empty(), summary.mainScript()),
                () -> assertTrue(!summary.canStopAll()));
    }

    @Test
    void summary_countsQueuedStarts_soStopAllCanCancelThem() {
        ClientGroup group = group("Waiting", BRACKEN);
        MemberFacts closed = linked(BRACKEN, "Brackenridge", MemberLink.CLOSED, fact(DIVINATION, ScriptState.QUEUED));

        GroupSummary summary = GroupViews.summary(group, snapshot(List.of(group), closed));

        assertAll(
                () -> assertEquals(0, summary.stoppable()),
                () -> assertEquals(1, summary.queued()),
                () -> assertTrue(summary.canStopAll()));
    }

    @Test
    void rows_offerTheActionEachStateAllows() {
        GroupDetail detail = GroupViews.detail(WOODCUTTERS, everyState(WOODCUTTERS));

        assertEquals(List.of(RowAction.STOP, RowAction.NONE, RowAction.STOP, RowAction.RESTART, RowAction.STOP,
                RowAction.NONE), detail.rows().stream().map(GroupViewsTest::actionOf).toList());
    }

    @Test
    void rows_offerRun_forAStoppedScript_andCancel_forAQueuedStartOnAClosedClient() {
        ClientGroup group = group("Mixed", OAK, BRACKEN);
        GroupsSnapshot snapshot = snapshot(List.of(group),
                connected(OAK, "Oakheart", fact(WOODCUTTING, ScriptState.STOPPED)),
                linked(BRACKEN, "Brackenridge", MemberLink.CLOSED, fact(DIVINATION, ScriptState.QUEUED)));

        List<RowAction> actions = GroupViews.detail(group, snapshot).rows().stream()
                .map(GroupViewsTest::actionOf).toList();

        assertEquals(List.of(RowAction.RUN, RowAction.CANCEL_QUEUED), actions);
    }

    @Test
    void rows_endWithTheUnresolvedMembers_keyedByTheirPipe() {
        ClientGroup group = withUnresolved(group("Old", OAK), PIPE);

        GroupDetail detail = GroupViews.detail(group, snapshot(List.of(group), connected(OAK, "Oakheart")));

        MemberRow.Unresolved last = assertInstanceOf(MemberRow.Unresolved.class, detail.rows().getLast());
        assertAll(
                () -> assertEquals(PIPE, last.pipe()),
                () -> assertEquals(List.of(OAK, ClientKey.PIPE_PREFIX + PIPE), detail.keys()));
    }

    @Test
    void rows_nameTheOtherGroupsAMemberIsIn() {
        ClientGroup woodcutters = group("Woodcutters", OAK);
        ClientGroup yews = group("Yews", OAK);
        GroupsSnapshot snapshot = snapshot(List.of(woodcutters, yews), connected(OAK, "Oakheart"));

        MemberRow.Account row = assertInstanceOf(MemberRow.Account.class,
                GroupViews.detail(woodcutters, snapshot).rows().getFirst());

        assertEquals(List.of("Yews"), row.otherGroups());
    }

    @Test
    void rows_showTheScriptThatNeedsALookFirst_andCountTheRest() {
        ClientGroup group = group("Busy", OAK);
        GroupsSnapshot snapshot = snapshot(List.of(group), connected(OAK, "Oakheart",
                ScriptFact.running(WOODCUTTING, OAK_LOOP), fact(DIVINATION, ScriptState.CRASHED)));

        MemberRow.Account row = assertInstanceOf(MemberRow.Account.class,
                GroupViews.detail(group, snapshot).rows().getFirst());

        assertAll(
                () -> assertEquals(DIVINATION, row.primary().orElseThrow().name()),
                () -> assertEquals(1, row.moreScripts()));
    }

    @Test
    void aMemberTheHostDoesNotKnow_isClosed_andNamedByItsUuid() {
        ClientGroup group = group("Stale", OAK);

        MemberRow.Account row = assertInstanceOf(MemberRow.Account.class,
                GroupViews.detail(group, snapshot(List.of(group))).rows().getFirst());

        assertAll(
                () -> assertEquals(OAK, row.account()),
                () -> assertEquals(MemberHealth.CLOSED, row.facts().health()));
    }

    @Test
    void ungrouped_countsTheClientsInNoGroup() {
        GroupsSnapshot snapshot = snapshot(List.of(group("One", OAK)),
                connected(OAK, "Oakheart"), connected(WREN, "Wrenfield"), connected(DUSK, "Duskwater"));

        assertEquals(2, GroupViews.ungrouped(snapshot));
    }

    @Test
    void candidates_leaveOutTheGroupsMembers_andMatchTheQuery() {
        ClientGroup group = group("One", OAK);
        GroupsSnapshot snapshot = snapshot(List.of(group),
                connected(OAK, "Oakheart"), connected(WREN, "Wrenfield"), connected(DUSK, "Duskwater"));

        assertAll(
                () -> assertEquals(List.of("Wrenfield", "Duskwater"), names(
                        GroupViews.candidates(snapshot, Optional.of(group), ""))),
                () -> assertEquals(List.of("Duskwater"), names(
                        GroupViews.candidates(snapshot, Optional.of(group), "DUSK"))),
                () -> assertEquals(List.of("Oakheart", "Wrenfield", "Duskwater"), names(
                        GroupViews.candidates(snapshot, Optional.empty(), ""))));
    }

    @Test
    void candidates_matchTheShortUuid_andKeepRefusedClients_withTheirReason() {
        PickableClient refused = new PickableClient(new ClientKey.Pipe("BotWithUs_1"), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.of("no account"));
        GroupsSnapshot snapshot = new GroupsSnapshot(List.of(), Map.of(), List.of(refused));

        List<PickableClient> all = GroupViews.candidates(snapshot, Optional.empty(), "");

        assertAll(
                () -> assertEquals(List.of(refused), all),
                () -> assertEquals(Optional.of("no account"), all.getFirst().refusal()),
                () -> assertEquals(List.of(), GroupViews.candidates(
                        snapshot(List.of(), connected(OAK, "Oakheart")), Optional.empty(), "zzz")),
                () -> assertEquals(1, GroupViews.candidates(
                        snapshot(List.of(), connected(OAK, "Oakheart")), Optional.empty(), "3f9a1c").size()));
    }

    private static RowAction actionOf(MemberRow row) {
        return switch (row) {
            case MemberRow.Account account -> account.action();
            case MemberRow.Unresolved _ -> RowAction.NONE;
        };
    }

    private static List<String> names(List<PickableClient> clients) {
        return clients.stream().map(GroupText::nameOf).toList();
    }
}
