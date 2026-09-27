package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.management.Target;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which script UIs are in windows of their own: one window per runner, closed
 * when the runner goes, and remembered for the session until brought back.
 */
class ScriptUiPopoutsTest {

    private static final ClientScript ON_OAKHEART = new ClientScript(TestScripts.PIPE, "Example Script");
    private static final ClientScript ON_FERNMOSS = new ClientScript("BotWithUs_9001", "Example Script");
    private static final ManagementScript FLEET = new ManagementScript("Fleet Monitor");
    private static final Target HOST = Target.host();

    private final ScriptUiPopouts popouts = new ScriptUiPopouts();
    private final AtomicBoolean isGone = new AtomicBoolean(false);

    private InspectorTarget target(InspectorSubject subject, ScriptUI ui) {
        ScriptInfo info = new ScriptInfo(subject.scriptName(), "", "", ScriptCategory.OTHER, "", 0, ui != null);
        return new InspectorTarget(subject, "", info, List.of(), () -> new ScriptConfig(Map.of()),
                cfg -> { }, ui, id -> Optional.empty(), isGone::get);
    }

    /** Every subject resolves, drawing a UI, while its runner is not gone. */
    private Optional<InspectorTarget> withUi(InspectorSubject subject) {
        return Optional.of(target(subject, TestScripts.NO_OP_UI));
    }

    @Test
    void aManagementScriptHasOneWindow_whicheverTargetItsFormIsOn() {
        ManagementScript onHost = FLEET.withSettingsFor(Optional.of(HOST));

        popouts.popOut(onHost);

        assertAll(
                () -> assertEquals(FLEET, ScriptUiPopouts.windowOf(onHost)),
                () -> assertTrue(popouts.isPoppedOut(FLEET)),
                () -> assertEquals(ScriptUiPopouts.imguiIdOf(FLEET), ScriptUiPopouts.imguiIdOf(onHost)));
    }

    @Test
    void theWindowId_isAnImGuiIdSuffix_distinctForTheSameScriptOnTwoClients() {
        String id = ScriptUiPopouts.imguiIdOf(ON_OAKHEART);

        assertAll(
                () -> assertTrue(id.startsWith("###"), "the title before it can change without moving the window"),
                () -> assertEquals(id, ScriptUiPopouts.imguiIdOf(new ClientScript(TestScripts.PIPE, "Example Script"))),
                () -> assertNotEquals(id, ScriptUiPopouts.imguiIdOf(ON_FERNMOSS)),
                () -> assertNotEquals(id, ScriptUiPopouts.imguiIdOf(new ManagementScript("Example Script"))));
    }

    @Test
    void resolve_listsTheOpenWindows_inTheOrderTheyWerePoppedOut() {
        popouts.popOut(ON_FERNMOSS);
        popouts.popOut(ON_OAKHEART);

        List<InspectorSubject> shown = popouts.resolve(this::withUi).stream().map(InspectorTarget::subject).toList();

        assertEquals(List.of(ON_FERNMOSS, ON_OAKHEART), shown);
    }

    @Test
    void bringingBack_closesTheWindow_andForgetsIt() {
        popouts.popOut(ON_OAKHEART);

        popouts.bringBack(ON_OAKHEART);
        popouts.reopenIfRemembered(ON_OAKHEART);

        assertAll(
                () -> assertFalse(popouts.isPoppedOut(ON_OAKHEART)),
                () -> assertTrue(popouts.resolve(this::withUi).isEmpty()));
    }

    @Test
    void aWindowWhoseRunnerIsGone_closes_butIsRemembered_andReopensWithItsScriptUiTab() {
        popouts.popOut(ON_OAKHEART);
        isGone.set(true);

        assertTrue(popouts.resolve(this::withUi).isEmpty());
        assertFalse(popouts.isPoppedOut(ON_OAKHEART));

        isGone.set(false);
        popouts.reopenIfRemembered(ON_OAKHEART);
        assertEquals(1, popouts.resolve(this::withUi).size());
    }

    @Test
    void aWindowWhoseScriptIsMissingOrDrawsNoUi_closes() {
        popouts.popOut(ON_OAKHEART);
        popouts.popOut(ON_FERNMOSS);

        List<InspectorTarget> shown = popouts.resolve(subject -> subject.equals(ON_OAKHEART)
                ? Optional.empty() : Optional.of(target(subject, null)));

        assertAll(
                () -> assertTrue(shown.isEmpty()),
                () -> assertFalse(popouts.isPoppedOut(ON_OAKHEART)),
                () -> assertFalse(popouts.isPoppedOut(ON_FERNMOSS)));
    }

    @Test
    void reopenIfRemembered_doesNothing_forAScriptNeverPoppedOut() {
        popouts.reopenIfRemembered(ON_OAKHEART);

        assertFalse(popouts.isPoppedOut(ON_OAKHEART));
    }
}
