package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.cli.events.ClientKey;
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
            choice("p1", "Oakheart", true, ""),
            choice("p2", "Hollowmere", false, "reconnecting"),
            choice("p3", "Wrenfield", true, ""),
            choice("p4", "Duskwater", true, ""),
            choice("p5", "Tamsin Vale", true, ""),
            choice("p6", "Ashgrove", true, ""),
            choice("p7", "Quillon", true, ""));
    private static final String UUID = "7d3e5f71a9b24c6d8e0f1a2b3c4d5e6f";

    /** A client on its own account, which the host remembers. */
    private static ClientChoice choice(String id, String name, boolean isConnected, String offlineNote) {
        return new ClientChoice(id, name, isConnected, offlineNote, ClientKey.account("account-of-" + id));
    }

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
        assertEquals(Eligibility.WHEN_BACK, target(targets, "p2").eligibility());
        assertEquals("starts when back", target(targets, "p2").note());
        assertEquals(Eligibility.ALREADY_RUNNING, target(targets, "p3").eligibility());
        assertEquals("stopped here", target(targets, "p4").note());
        assertEquals("crashed here", target(targets, "p5").note());
        assertEquals(Eligibility.SHUTTING_DOWN, target(targets, "p6").eligibility());
        assertEquals("idle", target(targets, "p7").note());
        assertEquals(List.of("p2", "p4", "p5", "p7"),
                targets.stream().filter(StartTarget::isSelectable).map(StartTarget::clientId).toList());
    }

    @Test
    void aRememberedClientThatIsNotConnected_canBeTicked_andStartsWhenItIsBack() {
        ClientChoice closed = new ClientChoice(UUID, "Brackenridge", false, "game closed", ClientKey.account(UUID));

        StartTarget target = StartTargets.of(local("X", ScriptCategory.UTILITY, "x.jar"), List.of(closed)).getFirst();

        assertTrue(target.isSelectable());
        assertTrue(target.startsWhenBack());
        assertFalse(target.isConnected());
        assertEquals(Optional.empty(), target.eligibility().tooltip());
    }

    @Test
    void anOfflineClientTheHostCannotRecognise_isShownDisabled_withTheReason() {
        List<ClientChoice> clients = List.of(
                new ClientChoice("BotWithUs_9", "BotWithUs_9", false, "client closed", ClientKey.pipe("BotWithUs_9")),
                new ClientChoice("BotWithUs_8", "Ashgrove", false, "reconnecting", new ClientKey.Account(UUID, 2)));

        List<StartTarget> targets = StartTargets.of(local("X", ScriptCategory.UTILITY, "x.jar"), clients);

        assertEquals(List.of(Eligibility.OFFLINE_NO_ACCOUNT, Eligibility.OFFLINE_SECOND_CLIENT),
                targets.stream().map(StartTarget::eligibility).toList());
        assertTrue(targets.stream().noneMatch(StartTarget::isSelectable));
        assertTrue(targets.stream().allMatch(t -> t.eligibility().tooltip().isPresent()));
        assertEquals("client closed", targets.getFirst().note());
    }

    @Test
    void aStoreScript_cannotWaitForAClientThatIsNotConnected() {
        StartTarget offline = target(StartTargets.of(Rows.store("Div", ScriptCategory.DIVINATION), CLIENTS), "p2");

        assertFalse(offline.isSelectable());
        assertEquals(Eligibility.OFFLINE_STORE_SCRIPT, offline.eligibility());
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
