package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.gui.AppMode;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.management.Target;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing: a "Settings" button anywhere opens the one inspector and switches to
 * the page that owns the script, in a mode that shows that page, and pops a
 * script's own UI out into a window that floats over whichever page is shown.
 */
class InspectorStateTest {

    private static final ClientScript WOODCUTTING = new ClientScript(TestScripts.PIPE, "Woodcutting");
    private static final InspectorSubject.ManagementScript BREAKS =
            new InspectorSubject.ManagementScript("Break Scheduler");
    private static final ScriptInfo INFO =
            new ScriptInfo("Woodcutting", "", "", ScriptCategory.WOODCUTTING, "", 0, false);

    private final AtomicReference<Instant> now = new AtomicReference<>(TestScripts.T0);
    private final InspectorState state = new InspectorState(now::get);
    private final PageRegistry pages = everyPage();

    /** A page with nothing to draw; routing only reads its id. */
    private record StubPage(PageId id) implements Page {
        @Override
        public void render() {
        }
    }

    private static PageRegistry everyPage() {
        return new PageRegistry(Arrays.stream(PageId.values()).<Page>map(StubPage::new).toList());
    }

    private static InspectorTarget target(InspectorSubject subject, AtomicBoolean gone) {
        return new InspectorTarget(subject, "", INFO, List.of(), () -> new ScriptConfig(Map.of()),
                cfg -> { }, null, id -> Optional.empty(), gone::get);
    }

    @Test
    void clientScript_fromInstalledScripts_opensOnTheClientsPage() {
        pages.select(PageId.INSTALLED);
        state.request(new InspectorRequest(WOODCUTTING, InspectorTab.SETTINGS));

        AppMode mode = state.route(pages, AppMode.ADVANCED);

        assertAll(
                () -> assertEquals(AppMode.ADVANCED, mode),
                () -> assertEquals(PageId.CLIENTS, pages.selected()),
                () -> assertEquals(Optional.of(WOODCUTTING), state.subject()),
                () -> assertTrue(state.docksBeside(PageId.CLIENTS)),
                () -> assertFalse(state.docksBeside(PageId.INSTALLED)));
    }

    @Test
    void clientScript_inNormalMode_staysInNormalMode_whichShowsTheClientsPage() {
        state.request(new InspectorRequest(WOODCUTTING, InspectorTab.SCRIPT_UI));

        assertEquals(AppMode.NORMAL, state.route(pages, AppMode.NORMAL));
        assertEquals(InspectorTab.SCRIPT_UI, state.tab());
    }

    @Test
    void managementScript_switchesToAdvanced_andTheManagementPage() {
        state.request(new InspectorRequest(BREAKS, InspectorTab.SETTINGS));

        AppMode mode = state.route(pages, AppMode.NORMAL);

        assertAll(
                () -> assertEquals(AppMode.ADVANCED, mode),
                () -> assertEquals(PageId.MANAGEMENT, pages.selected()),
                () -> assertTrue(state.docksBeside(PageId.MANAGEMENT)),
                () -> assertFalse(state.docksBeside(PageId.CLIENTS)));
    }

    @Test
    void settingsOnAScriptWithOnlyAUi_popsItOut_andLeavesThePageTheModeAndTheDrawerAlone() {
        pages.select(PageId.DASHBOARD);
        state.request(InspectorRequest.settings(WOODCUTTING, false, true));

        AppMode mode = state.route(pages, AppMode.NORMAL);

        assertAll(
                () -> assertEquals(AppMode.NORMAL, mode),
                () -> assertEquals(PageId.DASHBOARD, pages.selected()),
                () -> assertFalse(state.isOpen(), "no near-empty drawer beside the window"),
                () -> assertTrue(state.isPoppedOut(WOODCUTTING)));
    }

    @Test
    void settingsOnAScriptWithFieldsAndAUi_opensTheFieldsInTheDrawer_andPopsTheUiOut() {
        pages.select(PageId.INSTALLED);
        state.request(InspectorRequest.settings(WOODCUTTING, true, true));

        AppMode mode = state.route(pages, AppMode.ADVANCED);

        assertAll(
                () -> assertEquals(AppMode.ADVANCED, mode),
                () -> assertEquals(PageId.CLIENTS, pages.selected()),
                () -> assertEquals(Optional.of(WOODCUTTING), state.subject()),
                () -> assertEquals(InspectorTab.SETTINGS, state.tab()),
                () -> assertTrue(state.isPoppedOut(WOODCUTTING)));
    }

    @Test
    void settingsAgain_afterTheWindowWasClosed_popsItOutAgain() {
        state.show(InspectorRequest.settings(WOODCUTTING, false, true));
        state.bringBack(WOODCUTTING);
        assertFalse(state.isPoppedOut(WOODCUTTING));

        state.show(InspectorRequest.settings(WOODCUTTING, false, true));

        assertTrue(state.isPoppedOut(WOODCUTTING));
    }

    @Test
    void aScriptWithNoUi_isNeverPoppedOut() {
        state.request(InspectorRequest.settings(WOODCUTTING, true, false));
        state.route(pages, AppMode.NORMAL);

        assertAll(
                () -> assertTrue(state.isOpen()),
                () -> assertFalse(state.isPoppedOut(WOODCUTTING)));
    }

    @Test
    void thePoppedOutWindows_closeOnceTheirRunnerIsDisposed() {
        AtomicBoolean gone = new AtomicBoolean(false);
        InspectorTarget target = new InspectorTarget(WOODCUTTING, "", INFO, List.of(),
                () -> new ScriptConfig(Map.of()), cfg -> { }, TestScripts.NO_OP_UI, id -> Optional.empty(), gone::get);
        state.show(InspectorRequest.scriptUi(WOODCUTTING));

        assertEquals(List.of(target), state.poppedOut(subject -> Optional.of(target)));
        gone.set(true);

        assertTrue(state.poppedOut(subject -> Optional.of(target)).isEmpty());
        assertFalse(state.isPoppedOut(WOODCUTTING));
    }

    @Test
    void noRequest_changesNothing() {
        pages.select(PageId.GROUPS);

        assertEquals(AppMode.NORMAL, state.route(pages, AppMode.NORMAL));
        assertEquals(PageId.GROUPS, pages.selected());
        assertFalse(state.isOpen());
    }

    @Test
    void aRequestIsOpenedOnce_andTheLatestWins() {
        state.request(new InspectorRequest(WOODCUTTING, InspectorTab.SETTINGS));
        state.request(new InspectorRequest(BREAKS, InspectorTab.SETTINGS));
        state.route(pages, AppMode.ADVANCED);
        pages.select(PageId.DASHBOARD);

        assertEquals(Optional.of(BREAKS), state.subject());
        state.route(pages, AppMode.ADVANCED);
        assertEquals(PageId.DASHBOARD, pages.selected(), "a routed request does not route again");
    }

    /** The console's `scripts config` runs on the command thread and calls the same opener. */
    @Test
    void theHostsConfigOpener_requestsFromAnyThread_andRoutesToTheRunnersClient() throws InterruptedException {
        CliContext ctx = new CliContext(new LogBuffer(), null);
        ctx.setConfigPanelOpener(state.clientScriptOpener());
        pages.select(PageId.DASHBOARD);

        Thread command = Thread.ofVirtual().start(
                () -> ctx.openConfigPanel(TestScripts.clientRunner(new TestScripts.Woodcutting())));
        command.join();
        AppMode mode = state.route(pages, AppMode.ADVANCED);

        assertAll(
                () -> assertEquals(AppMode.ADVANCED, mode),
                () -> assertEquals(PageId.CLIENTS, pages.selected()),
                () -> assertEquals(Optional.of(WOODCUTTING), state.subject()),
                () -> assertEquals(InspectorTab.SETTINGS, state.tab()));
    }

    @Test
    void theManagementOpener_routesToManagement() {
        state.managementScriptOpener().accept(TestScripts.managementRunner(new TestScripts.BreakScheduler()));

        assertEquals(AppMode.ADVANCED, state.route(pages, AppMode.NORMAL));
        assertEquals(Optional.of(BREAKS), state.subject());
    }

    @Test
    void aSubjectThatHasNotAppearedYet_staysOpenForTheGrace_thenCloses() {
        state.open(WOODCUTTING, InspectorTab.SETTINGS);
        InspectorSource nothing = subject -> Optional.empty();

        assertTrue(state.resolve(nothing).isEmpty());
        assertTrue(state.isOpen(), "a script still starting keeps its inspector");

        now.set(TestScripts.T0.plus(InspectorState.STARTING_GRACE));
        state.resolve(nothing);
        assertFalse(state.isOpen());
    }

    @Test
    void aRunnerThatWasShownAndIsNowDisposed_closesAtOnce() {
        AtomicBoolean gone = new AtomicBoolean(false);
        InspectorTarget target = target(WOODCUTTING, gone);
        state.open(WOODCUTTING, InspectorTab.SETTINGS);

        assertEquals(Optional.of(target), state.resolve(subject -> Optional.of(target)));
        gone.set(true);

        assertTrue(state.resolve(subject -> Optional.of(target)).isEmpty());
        assertFalse(state.isOpen(), "no grace once it has been shown");
    }

    @Test
    void reopening_restartsTheGrace() {
        state.open(WOODCUTTING, InspectorTab.SETTINGS);
        now.set(TestScripts.T0.plus(Duration.ofHours(1)));
        state.open(BREAKS, InspectorTab.SETTINGS);

        state.resolve(subject -> Optional.empty());

        assertTrue(state.isOpen());
    }

    @Test
    void pickingATarget_reopensTheManagementScriptOnIt_asItsOwnSubject_onSettings() {
        Target oakheart = new Target.ClientScript("3f9a1c2e58b04d7a9e216c0f4b7d2a18", "Woodcutting");
        state.open(BREAKS, InspectorTab.SCRIPT_UI);

        state.pickSettingsFor(Optional.of(oakheart));

        assertAll(
                () -> assertEquals(Optional.of(BREAKS.withSettingsFor(Optional.of(oakheart))), state.subject()),
                () -> assertNotEquals(Optional.of(BREAKS), state.subject(), "a fresh form, not the defaults'"),
                () -> assertEquals(InspectorTab.SETTINGS, state.tab()),
                () -> assertTrue(state.docksBeside(PageId.MANAGEMENT)));
    }

    @Test
    void pickingTheDefaults_goesBackToTheDefaultsSubject() {
        state.open(BREAKS.withSettingsFor(Optional.of(Target.host())), InspectorTab.SETTINGS);

        state.pickSettingsFor(Optional.empty());

        assertEquals(Optional.of(BREAKS), state.subject());
    }

    @Test
    void pickingATarget_leavesAClientScriptAndAClosedInspectorAlone() {
        state.pickSettingsFor(Optional.of(Target.host()));
        assertFalse(state.isOpen());

        state.open(WOODCUTTING, InspectorTab.SCRIPT_UI);
        state.pickSettingsFor(Optional.of(Target.host()));

        assertAll(
                () -> assertEquals(Optional.of(WOODCUTTING), state.subject()),
                () -> assertEquals(InspectorTab.SCRIPT_UI, state.tab()));
    }
}
