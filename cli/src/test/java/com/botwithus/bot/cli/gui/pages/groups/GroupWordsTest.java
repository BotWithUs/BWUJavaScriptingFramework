package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.BRACKEN;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.DIVINATION;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.OAK;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.WOODCUTTING;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.WREN;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.connected;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.fact;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.group;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.linked;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.managed;
import static com.botwithus.bot.cli.gui.pages.groups.GroupFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the page says: the Stop all question, the "What happens" lines and the action reports. */
class GroupWordsTest {

    private static final double LOOP_MS = 100.0;

    @Test
    void stopQuestion_countsScripts_andSaysTheManagerPauses_onlyWhenOneIsSetToRun() {
        ClientGroup plain = group("Woodcutters", OAK, BRACKEN);
        GroupsSnapshot snapshot = snapshot(List.of(plain),
                connected(OAK, "Oakheart", ScriptFact.running(WOODCUTTING, LOOP_MS)),
                linked(BRACKEN, "Brackenridge", MemberLink.CLOSED, fact(DIVINATION, ScriptState.QUEUED)));
        ClientGroup running = managed(plain, "Break Scheduler", true);
        ClientGroup paused = managed(plain, "Break Scheduler", false);

        assertAll(
                () -> assertEquals("Stop 1 script in Woodcutters? 1 queued start is cancelled.",
                        GroupHeader.stopQuestion(GroupViews.detail(plain, snapshot))),
                () -> assertTrue(GroupHeader.stopQuestion(GroupViews.detail(running, snapshot))
                        .endsWith(" The manager pauses too.")),
                () -> assertFalse(GroupHeader.stopQuestion(GroupViews.detail(paused, snapshot)).contains("manager")));
    }

    @Test
    void planLines_nameEachKindOfMember_andSayHonestlyThatAlsoStartingStopsNothing() {
        ClientGroup group = group("Mixed", OAK, WREN, BRACKEN);
        GroupsSnapshot snapshot = snapshot(List.of(group),
                connected(OAK, "Oakheart", ScriptFact.running(DIVINATION, LOOP_MS)),
                connected(WREN, "Wrenfield", ScriptFact.running(WOODCUTTING, LOOP_MS)),
                linked(BRACKEN, "Brackenridge", MemberLink.CLOSED));

        List<String> lines = PlanLines.of(StartPlan.of(group, snapshot, WOODCUTTING, BusyChoice.ALSO_START))
                .stream().map(PlanLines.Line::text).toList();

        assertAll(
                () -> assertEquals(List.of(
                        "Starts on 1: Oakheart",
                        "1 is running something else (Oakheart: Divination).",
                        "Already running on 1: Wrenfield. Nothing changes there.",
                        "1 not connected (Brackenridge) starts it when it comes back."), lines),
                () -> assertTrue(PlanLines.note(BusyChoice.ALSO_START).contains("several scripts at once")),
                () -> assertTrue(PlanLines.note(BusyChoice.SWITCH).contains("stopped first")));
    }

    @Test
    void startedNotice_saysWhatStartedStoppedAndQueued() {
        ClientGroup group = group("Mixed", OAK, WREN, BRACKEN);
        GroupsSnapshot snapshot = snapshot(List.of(group),
                connected(OAK, "Oakheart", ScriptFact.running(DIVINATION, LOOP_MS)),
                connected(WREN, "Wrenfield"),
                linked(BRACKEN, "Brackenridge", MemberLink.CLOSED));

        String text = GroupNotices.started(StartPlan.of(group, snapshot, WOODCUTTING, BusyChoice.SWITCH));

        assertEquals("Starting Woodcutting on 2 clients. Stopped what was running on 1 client first."
                + " 1 client starts it when back.", text);
    }

    @Test
    void addedNotice_isAProblem_whenAnyClientWasRefused_andCarriesTheReason() {
        Notice ok = GroupNotices.added("Woodcutters", List.of("Oakheart"), List.of());
        Notice refused = GroupNotices.added("Woodcutters", List.of("Oakheart"), List.of("pipe:X: no account"));

        assertAll(
                () -> assertEquals(Notice.info("Added 1 client to Woodcutters."), ok),
                () -> assertTrue(refused.isProblem()),
                () -> assertEquals("Added 1 client to Woodcutters. Could not add pipe:X: no account", refused.text()));
    }

    @Test
    void dialogButtons_sayWhatTheyWillDo() {
        assertAll(
                () -> assertEquals("Add 1 client", PickClientsDialog.okLabel(true, 1)),
                () -> assertEquals("Create group", PickClientsDialog.okLabel(false, 0)),
                () -> assertEquals("Create group with 3", PickClientsDialog.okLabel(false, 3)));
    }
}
