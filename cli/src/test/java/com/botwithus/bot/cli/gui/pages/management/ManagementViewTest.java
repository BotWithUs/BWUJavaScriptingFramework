package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.management.Target;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the page words a script's targets, state and loop, from the view records alone. */
class ManagementViewTest {

    private static final TargetRow HOST = new TargetRow(Target.host(), "Whole host", "every connected client", 0);
    private static final TargetRow WOODCUTTERS = new TargetRow(new Target.Group(GroupId.migratedFrom("Woodcutters")),
            "Woodcutters", "group · 4 clients", 2);
    private static final TargetRow OAKHEART = new TargetRow(new Target.ClientScript("3f9a1c2e", "Woodcutting"),
            "Oakheart · Woodcutting", "one client's script", 0);
    private static final TargetRow FERNMOSS = new TargetRow(new Target.ClientScript("b71d09e4", "Divination"),
            "Fernmoss · Divination", "one client's script", 1);
    private static final TargetRow KESTREL = new TargetRow(new Target.ClientScript("81c5e0b2", "Walk to Flag"),
            "Kestrel Moor · Walk to Flag", "one client's script", 0);

    private static ManagementRow row(String name, List<TargetRow> targets, RunState state) {
        ScriptAbout about = new ScriptAbout(name, "1.0", "", "You", "com.example." + name.replace(" ", ""), 2,
                false);
        return new ManagementRow(about, targets,
                new RunHealth(state, OptionalDouble.of(4.2), 10, 0, Optional.empty()), List.of());
    }

    @Test
    void appliesTo_namesTwoTargets_thenFoldsTheRestIntoPlusN() {
        AppliesTo four = AppliesTo.of(List.of(WOODCUTTERS, OAKHEART, FERNMOSS, KESTREL));
        AppliesTo two = AppliesTo.of(List.of(WOODCUTTERS, OAKHEART));

        assertAll(
                () -> assertEquals(List.of(WOODCUTTERS, OAKHEART), four.shown()),
                () -> assertEquals("+2", four.more()),
                () -> assertEquals("Fernmoss · Divination, Kestrel Moor · Walk to Flag", four.hiddenLabels()),
                () -> assertEquals(List.of(WOODCUTTERS, OAKHEART), two.shown()),
                () -> assertEquals("", two.more(), "nothing folded, no +N"),
                () -> assertFalse(two.isNotApplied()));
    }

    @Test
    void aScriptWithNoTargets_isNotApplied() {
        ManagementRow none = row("World Balancer", List.of(), new RunState.Stopped());

        assertAll(
                () -> assertTrue(none.appliesTo().isNotApplied()),
                () -> assertEquals("Not applied", AppliesTo.NOT_APPLIED),
                () -> assertEquals("Not applied yet", none.appliesLine()),
                () -> assertEquals("Applies to Whole host",
                        row("Restart on Crash", List.of(HOST), new RunState.Stopped()).appliesLine()),
                () -> assertEquals("Applies to 3 targets",
                        row("Break Scheduler", List.of(WOODCUTTERS, OAKHEART, FERNMOSS),
                                new RunState.Stopped()).appliesLine()));
    }

    @Test
    void theState_readsRunningWithItsUptime_stoppedOrCrashed_andTheLoopOnlyWhileRunning() {
        RunState running = new RunState.Running(Duration.ofMinutes(42).plusSeconds(10));
        RunState hours = new RunState.Running(Duration.ofHours(2).plusMinutes(14).plusSeconds(3));
        ManagementRow stopped = row("World Balancer", List.of(), new RunState.Stopped());

        assertAll(
                () -> assertEquals("Running", running.label()),
                () -> assertEquals("42:10", running.detail()),
                () -> assertEquals("2:14:03", hours.detail()),
                () -> assertEquals("Crashed", new RunState.Crashed("IllegalStateException in onStart()").label()),
                () -> assertEquals("", new RunState.Stopped().detail()),
                () -> assertEquals("4", row("Break Scheduler", List.of(), running).health().loopText()),
                () -> assertEquals("—", stopped.health().loopText(), "no loop while stopped"),
                () -> assertEquals("running 42:10", row("B", List.of(), running).health().stateLine()));
    }

    @Test
    void theView_countsWhatIsLoadedAndRunning_andWhatNeedsALook() {
        ManagementView view = new ManagementView("scripts/management/", false, List.of(
                row("Break Scheduler", List.of(WOODCUTTERS), new RunState.Running(Duration.ZERO)),
                row("Restart on Crash", List.of(HOST), new RunState.Running(Duration.ZERO)),
                row("World Balancer", List.of(), new RunState.Crashed("IllegalStateException in onStart()"))),
                List.of(), TargetChoices.NONE);

        assertAll(
                () -> assertEquals("scripts/management/ · 3 loaded · 2 running", view.meta()),
                () -> assertEquals(1, view.attentionCount(), "the crashed script"),
                () -> assertFalse(view.isEmpty()),
                () -> assertTrue(new ManagementView("scripts/management/", false, List.of(), List.of(),
                        TargetChoices.NONE).isEmpty()));
    }

    @Test
    void theAddRow_offersOnlyWhatTheScriptDoesNotHave_andTheWholeHostUntilItIsAdded() {
        TargetChoices choices = new TargetChoices(
                List.of(new TargetChoices.Option(WOODCUTTERS.target(), "Woodcutters · 4 clients")),
                List.of(new TargetChoices.Option(OAKHEART.target(), OAKHEART.label()),
                        new TargetChoices.Option(FERNMOSS.target(), FERNMOSS.label())));
        ManagementRow breaks = row("Break Scheduler", List.of(WOODCUTTERS, OAKHEART), new RunState.Stopped());
        ManagementRow host = row("Restart on Crash", List.of(HOST), new RunState.Stopped());

        assertAll(
                () -> assertEquals(List.of(FERNMOSS.target()), TargetKind.CLIENT_SCRIPT.options(breaks, choices)
                        .stream().map(TargetChoices.Option::target).toList()),
                () -> assertEquals(List.of(), TargetKind.GROUP.options(breaks, choices)),
                () -> assertEquals(List.of(Target.host()), TargetKind.WHOLE_HOST.options(breaks, choices)
                        .stream().map(TargetChoices.Option::target).toList()),
                () -> assertEquals(List.of(), TargetKind.WHOLE_HOST.options(host, choices)));
    }
}
