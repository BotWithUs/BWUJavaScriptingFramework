package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.gui.pages.installed.StartTarget.Eligibility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.botwithus.bot.cli.gui.pages.installed.Rows.local;
import static com.botwithus.bot.cli.gui.pages.installed.Rows.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who the Start-on dialog lets you tick. */
class StartTargetsTest {

    private static final List<ClientChoice> CLIENTS = List.of(
            new ClientChoice("p1", "Oakheart", true, ""),
            new ClientChoice("p2", "Hollowmere", false, "reconnecting"),
            new ClientChoice("p3", "Wrenfield", true, ""),
            new ClientChoice("p4", "Duskwater", true, ""),
            new ClientChoice("p5", "Tamsin Vale", true, ""),
            new ClientChoice("p6", "Ashgrove", true, ""),
            new ClientChoice("p7", "Quillon", true, ""));

    private static StartTarget target(List<StartTarget> targets, String id) {
        return targets.stream().filter(t -> t.clientId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void eachClientOnce_inClientOrder_withWhyItCanOrCannotStart() {
        InstalledScript wc = local("Woodcutting", ScriptCategory.WOODCUTTING, "woodcutting.jar",
                run("p1", RunnerState.RUNNING), run("p3", RunnerState.STALLED), run("p4", RunnerState.STOPPED),
                run("p5", RunnerState.CRASHED), run("p6", RunnerState.CUT_OFF));

        List<StartTarget> targets = StartTargets.of(wc, CLIENTS);

        assertEquals(List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7"),
                targets.stream().map(StartTarget::clientId).toList());
        assertEquals(Eligibility.ALREADY_RUNNING, target(targets, "p1").eligibility());
        assertEquals("already running", target(targets, "p1").note());
        assertEquals(Eligibility.OFFLINE, target(targets, "p2").eligibility());
        assertEquals("reconnecting", target(targets, "p2").note());
        assertEquals(Eligibility.ALREADY_RUNNING, target(targets, "p3").eligibility());
        assertEquals("stopped here", target(targets, "p4").note());
        assertEquals("crashed here", target(targets, "p5").note());
        assertEquals(Eligibility.SHUTTING_DOWN, target(targets, "p6").eligibility());
        assertEquals("idle", target(targets, "p7").note());
        assertEquals(List.of("p4", "p5", "p7"),
                targets.stream().filter(StartTarget::isSelectable).map(StartTarget::clientId).toList());
    }

    @Test
    void anOfflineClient_isShownDisabled_withTheQueueComingLater() {
        StartTarget offline = target(StartTargets.of(local("X", ScriptCategory.UTILITY, "x.jar"), CLIENTS), "p2");

        assertFalse(offline.isSelectable());
        assertFalse(offline.isConnected());
        assertEquals(Optional.of("Queuing for offline clients comes with Groups"), offline.eligibility().tooltip());
    }

    @Test
    void aStoreScript_canOnlyStartWhereItWasInstalled() {
        InstalledScript store = new InstalledScript("Div", Rows.store("Div", ScriptCategory.DIVINATION).identity(),
                Rows.store("Div", ScriptCategory.DIVINATION).provenance(), List.of(), Set.of("p3"), List.of());

        List<StartTarget> targets = StartTargets.of(store, CLIENTS);

        assertTrue(target(targets, "p3").isSelectable());
        assertEquals(Eligibility.NOT_INSTALLED_HERE, target(targets, "p1").eligibility());
    }

    @Test
    void aLocalScript_canStartOnAClientThatNeverLoadedIt() {
        InstalledScript fresh = local("Probe", ScriptCategory.UTILITY, "probe.jar");

        assertTrue(target(StartTargets.of(fresh, CLIENTS), "p1").isSelectable());
    }
}
