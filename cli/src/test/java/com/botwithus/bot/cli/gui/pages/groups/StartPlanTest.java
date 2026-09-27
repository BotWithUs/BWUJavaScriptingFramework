package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;

import org.junit.jupiter.api.Test;

import java.util.List;

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
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.snapshot;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.withUnresolved;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Start script dialog's "What happens": which members start now, which are busy, which wait. */
class StartPlanTest {

    private static final double LOOP_MS = 100.0;

    private static final ClientGroup GROUP = withUnresolved(
            group("Mixed", OAK, HOLLOW, WREN, DUSK, FERN, BRACKEN), "BotWithUs_9");

    private static final GroupsSnapshot SNAPSHOT = snapshot(List.of(GROUP),
            connected(OAK, "Oakheart"),
            linked(HOLLOW, "Hollowmere", MemberLink.RECONNECTING),
            connected(WREN, "Wrenfield", ScriptFact.running(WOODCUTTING, LOOP_MS)),
            connected(DUSK, "Duskwater", fact(WOODCUTTING, ScriptState.STOPPED)),
            connected(FERN, "Fernmoss", fact(DIVINATION, ScriptState.STALLED)),
            linked(BRACKEN, "Brackenridge", MemberLink.CLOSED, fact(DIVINATION, ScriptState.QUEUED)));

    @Test
    void partitions_intoNow_busy_alreadyRunning_andQueued() {
        StartPlan plan = StartPlan.of(GROUP, SNAPSHOT, WOODCUTTING, BusyChoice.ALSO_START);

        assertAll(
                () -> assertEquals(List.of(OAK, DUSK), uuids(plan.startNow())),
                () -> assertEquals(List.of(FERN), uuids(plan.busy())),
                () -> assertEquals(List.of(DIVINATION), plan.busy().getFirst().others()),
                () -> assertEquals(List.of(WREN), uuids(plan.alreadyRunning())),
                () -> assertEquals(List.of(HOLLOW, BRACKEN), uuids(plan.queued()), "not connected: queued"),
                () -> assertEquals(1, plan.unresolved()));
    }

    @Test
    void busyMembers_startEitherWay_butOnlyASwitchStopsWhatTheyRun() {
        StartPlan also = StartPlan.of(GROUP, SNAPSHOT, WOODCUTTING, BusyChoice.ALSO_START);
        StartPlan swap = StartPlan.of(GROUP, SNAPSHOT, WOODCUTTING, BusyChoice.SWITCH);

        assertAll(
                () -> assertEquals(List.of(OAK, DUSK, FERN), uuids(also.startsNow())),
                () -> assertEquals(List.of(OAK, DUSK, FERN), uuids(swap.startsNow())),
                () -> assertFalse(also.stopsOthers()),
                () -> assertTrue(swap.stopsOthers()));
    }

    @Test
    void scriptNames_matchIgnoringCase_asTheRuntimeMatchesThem() {
        StartPlan plan = StartPlan.of(GROUP, SNAPSHOT, "WOODCUTTING", BusyChoice.SWITCH);

        assertEquals(List.of(WREN), uuids(plan.alreadyRunning()));
    }

    @Test
    void aGroupWhereItRunsEverywhere_hasNothingToStart() {
        ClientGroup group = group("Done", WREN);

        StartPlan plan = StartPlan.of(group, SNAPSHOT, WOODCUTTING, BusyChoice.SWITCH);

        assertAll(
                () -> assertFalse(plan.canStart()),
                () -> assertFalse(plan.stopsOthers()));
    }

    @Test
    void aGroupOfClosedClients_canStillStart_byQueuing() {
        ClientGroup group = group("Away", BRACKEN);

        StartPlan plan = StartPlan.of(group, SNAPSHOT, WOODCUTTING, BusyChoice.ALSO_START);

        assertAll(
                () -> assertTrue(plan.canStart()),
                () -> assertEquals(List.of(), plan.startsNow()),
                () -> assertEquals(List.of("Brackenridge"), plan.queued().stream()
                        .map(StartPlan.Member::account).toList()));
    }

    private static List<String> uuids(List<StartPlan.Member> members) {
        return members.stream().map(StartPlan.Member::uuid).toList();
    }
}
