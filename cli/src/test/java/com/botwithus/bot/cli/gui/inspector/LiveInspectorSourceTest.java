package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.LiveInspectorSource.ManagementAccess;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.Choice;
import com.botwithus.bot.cli.gui.inspector.SettingsFor.InheritedValue;
import com.botwithus.bot.cli.management.ManagementFile;
import com.botwithus.bot.cli.management.ManagementSettings;
import com.botwithus.bot.cli.management.ManagementTargets;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.management.TargetLabels;
import com.botwithus.bot.core.config.ManagementSettingsStore;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;
import com.botwithus.bot.core.runtime.ManagementScriptRuntime;
import com.botwithus.bot.core.runtime.RunnerListener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The management kind resolves from the management runtime, with no client
 * connected, over real targets and settings files in a temporary folder.
 */
class LiveInspectorSourceTest {

    private static final String BREAKS_NAME = "Break Scheduler";
    private static final InspectorSubject.ManagementScript BREAKS =
            new InspectorSubject.ManagementScript(BREAKS_NAME);
    private static final String OAKHEART = "3f9a1c2e58b04d7a9e216c0f4b7d2a18";
    private static final String FERNMOSS = "b71d09e42c614f3e8a571d9e0c4b6f33";
    private static final Target.ClientScript OAKHEART_WOODCUTTING = new Target.ClientScript(OAKHEART, "Woodcutting");
    private static final Target.ClientScript FERNMOSS_DIVINATION = new Target.ClientScript(FERNMOSS, "Divination");

    @TempDir
    Path dir;

    private ManagementScriptRuntime runtime;
    private GroupStore groups;
    private ManagementTargets targets;
    private ManagementSettings settings;
    private LiveInspectorSource source;

    @BeforeEach
    void newHost() {
        ManagementSettingsStore store = new ManagementSettingsStore(dir.resolve("config"), Runnable::run);
        runtime = new ManagementScriptRuntime(null, RunnerListener.NONE, store);
        groups = new GroupStore(new GroupsFile(dir.resolve(GroupsFile.FILE_NAME)));
        targets = new ManagementTargets(new ManagementFile(dir.resolve(ManagementFile.FILE_NAME)), groups);
        settings = new ManagementSettings(store, targets, groups);
        TargetLabels labels = new TargetLabels(groups,
                uuid -> Optional.ofNullable(Map.of(OAKHEART, "Oakheart").get(uuid)));
        source = new LiveInspectorSource(List::of, () -> runtime, new ManagementAccess(targets, settings, labels));
    }

    @Test
    void managementScript_resolvesToItsRunnersFieldsAndItsDefaults() {
        ManagementScriptRunner runner = runtime.registerScript(new TestScripts.BreakScheduler());

        InspectorTarget target = source.resolve(BREAKS).orElseThrow();

        assertAll(
                () -> assertEquals(BREAKS, target.subject()),
                () -> assertEquals(LiveInspectorSource.MANAGEMENT_CONTEXT, target.context()),
                () -> assertEquals("Break Scheduler", target.script().name()),
                () -> assertEquals("1.2", target.script().version()),
                () -> assertEquals(runner.getConfigFields(), target.fields()),
                () -> assertEquals(target.fields().size(), target.script().settingsCount()),
                () -> assertFalse(target.hasCustomUi()),
                () -> assertEquals("90", target.current().get().asMap().get("breakEvery"),
                        "nothing applied yet: the declared defaults"),
                () -> assertFalse(target.settingsFor().isShown(), "no targets, nothing to pick"),
                () -> assertFalse(target.isGone().getAsBoolean()));
    }

    @Test
    void managementScriptWithAUi_offersTheScriptUiTab() {
        runtime.registerScript(new TestScripts.FleetMonitor());

        InspectorTarget target = source.resolve(new InspectorSubject.ManagementScript("Fleet Monitor")).orElseThrow();

        assertAll(
                () -> assertTrue(target.hasCustomUi()),
                () -> assertTrue(target.fields().isEmpty()));
    }

    @Test
    void aDisposedManagementRunner_isGone() {
        ManagementScriptRunner runner = runtime.registerScript(new TestScripts.BreakScheduler());
        InspectorTarget target = source.resolve(BREAKS).orElseThrow();

        runner.dispose();

        assertTrue(target.isGone().getAsBoolean());
    }

    @Test
    void unknownScripts_andClientScriptsWithNoClient_resolveToNothing() {
        ManagementAccess access = new ManagementAccess(targets, settings, new TargetLabels(groups, uuid -> Optional.empty()));
        assertAll(
                () -> assertEquals(Optional.empty(), source.resolve(BREAKS)),
                () -> assertEquals(Optional.empty(),
                        source.resolve(new ClientScript(TestScripts.PIPE, "Woodcutting"))),
                () -> assertEquals(Optional.empty(),
                        new LiveInspectorSource(List::of, () -> null, access).resolve(BREAKS),
                        "no management runtime yet"));
    }

    @Test
    void itemNames_withNoClientConnected_areUnknown() {
        runtime.registerScript(new TestScripts.BreakScheduler());

        assertEquals(Optional.empty(), source.resolve(BREAKS).orElseThrow().itemName().apply(995));
    }

    /** Break Scheduler on Woodcutters (Oakheart, Fernmoss), Oakheart's Woodcutting and Fernmoss's Divination. */
    @Nested
    class SettingsForATarget {

        private GroupId woodcutters;
        private ManagementScriptRunner runner;

        @BeforeEach
        void targets() {
            runner = runtime.registerScript(new TestScripts.BreakScheduler());
            woodcutters = groups.create("Woodcutters", Optional.empty()).orElseThrow().id();
            groups.addMember(woodcutters, ClientKey.account(OAKHEART));
            groups.addMember(woodcutters, ClientKey.account(FERNMOSS));
            targets.add(BREAKS_NAME, new Target.Group(woodcutters));
            targets.add(BREAKS_NAME, OAKHEART_WOODCUTTING);
            targets.add(BREAKS_NAME, FERNMOSS_DIVINATION);
            runner.applyConfig(new ScriptConfig(Map.of("breakEvery", "60")));
            settings.apply(BREAKS_NAME, new Target.Group(woodcutters),
                    new ScriptConfig(Map.of("breakEvery", "120", "logOut", "false")), runner.getConfigFields());
        }

        private InspectorTarget resolved(Optional<Target> settingsFor) {
            return source.resolve(BREAKS.withSettingsFor(settingsFor)).orElseThrow();
        }

        @Test
        void onTheDefaults_thePickerListsEveryTarget_withItsOwnValueCount() {
            SettingsFor picker = resolved(Optional.empty()).settingsFor();

            assertAll(
                    () -> assertEquals(List.of(
                            new Choice(new Target.Group(woodcutters), "Woodcutters", 2),
                            new Choice(OAKHEART_WOODCUTTING, "Oakheart · Woodcutting", 0),
                            new Choice(FERNMOSS_DIVINATION, "b71d09e4 · Divination", 0)), picker.choices()),
                    () -> assertEquals(List.of(SettingsFor.DEFAULTS_CHOICE, "Woodcutters · 2 own",
                            "Oakheart · Woodcutting", "b71d09e4 · Divination"), picker.options()),
                    () -> assertEquals(0, picker.selectedIndex()),
                    () -> assertEquals(LiveInspectorSource.DEFAULTS_NOTE, picker.note()),
                    () -> assertEquals(Optional.empty(), picker.inheritedOf("breakEvery"), "the defaults inherit nothing"));
        }

        @Test
        void onAClientScript_eachFieldSaysWhereItsValueComesFrom() {
            InspectorTarget target = resolved(Optional.of(OAKHEART_WOODCUTTING));
            SettingsFor picker = target.settingsFor();

            assertAll(
                    () -> assertEquals(BREAKS.withSettingsFor(Optional.of(OAKHEART_WOODCUTTING)), target.subject()),
                    () -> assertEquals(2, picker.selectedIndex()),
                    () -> assertEquals(Optional.of(new InheritedValue("120", "Woodcutters")),
                            picker.inheritedOf("breakEvery")),
                    () -> assertEquals(Optional.of(new InheritedValue("false", "Woodcutters")),
                            picker.inheritedOf("logOut")),
                    () -> assertEquals(Optional.of(new InheritedValue("Light", TargetLabels.DEFAULTS)),
                            picker.inheritedOf("jitter")),
                    () -> assertEquals("120", target.current().get().asMap().get("breakEvery"), "the merged value"),
                    () -> assertTrue(picker.note().contains("its group, then the defaults"), picker.note()));
        }

        @Test
        void onAGroup_everyFieldInheritsFromTheDefaults() {
            SettingsFor picker = resolved(Optional.of(new Target.Group(woodcutters))).settingsFor();

            assertAll(
                    () -> assertEquals(Optional.of(new InheritedValue("60", TargetLabels.DEFAULTS)),
                            picker.inheritedOf("breakEvery")),
                    () -> assertTrue(picker.note().endsWith("follows the defaults."), picker.note()));
        }

        @Test
        void applyingOnATarget_keepsOnlyWhatDiffersFromTheInheritedValue() {
            InspectorTarget target = resolved(Optional.of(OAKHEART_WOODCUTTING));
            Map<String, String> form = new LinkedHashMap<>(target.current().get().asMap());
            form.put("quietHours", "22:00-06:00");

            target.apply().accept(new ScriptConfig(form));

            assertAll(
                    () -> assertEquals(Map.of("quietHours", "22:00-06:00"),
                            settings.view(BREAKS_NAME, OAKHEART_WOODCUTTING, runner.getConfigFields()).own()),
                    () -> assertEquals("60", runner.getCurrentConfig().asMap().get("breakEvery"),
                            "the defaults are untouched"));
        }

        @Test
        void applyingOnTheDefaults_goesThroughTheRunner_soTheScriptHearsOfIt() {
            InspectorTarget target = resolved(Optional.empty());

            target.apply().accept(new ScriptConfig(Map.of("breakEvery", "45")));

            assertAll(
                    () -> assertEquals("45", runner.getCurrentConfig().asMap().get("breakEvery")),
                    () -> assertEquals("45", resolved(Optional.empty()).current().get().asMap().get("breakEvery")));
        }

        @Test
        void aTargetThatWasRemoved_fallsBackToTheDefaults() {
            targets.remove(BREAKS_NAME, FERNMOSS_DIVINATION);

            InspectorTarget target = resolved(Optional.of(FERNMOSS_DIVINATION));

            assertAll(
                    () -> assertEquals(BREAKS, target.subject()),
                    () -> assertEquals(0, target.settingsFor().selectedIndex()),
                    () -> assertEquals("60", target.current().get().asMap().get("breakEvery")));
        }

        @Test
        void aScriptManagingTheWholeHost_hasNoPicker_andAPickedTargetShowsTheDefaults() {
            targets.add(BREAKS_NAME, Target.host());

            InspectorTarget target = resolved(Optional.of(OAKHEART_WOODCUTTING));

            assertAll(
                    () -> assertFalse(target.settingsFor().isShown()),
                    () -> assertEquals(BREAKS, target.subject()));
        }
    }
}
