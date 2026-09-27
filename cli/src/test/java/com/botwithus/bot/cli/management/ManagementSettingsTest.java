package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.management.ManagementSettings.Inherited;
import com.botwithus.bot.cli.management.ManagementSettings.Source;
import com.botwithus.bot.cli.management.ManagementSettings.TargetView;
import com.botwithus.bot.core.config.ManagementSettingsStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real precedence over real {@code groups.json}, {@code management.json}
 * and settings files in a temporary folder.
 */
class ManagementSettingsTest {

    private static final String BREAKS = "Break Scheduler";
    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String UUID_C = "00000000111111112222222233333333";
    private static final String WOODCUTTING = "Woodcutting";
    private static final String DIVINATION = "Divination";
    private static final Target.ClientScript A_WOODCUTTING = new Target.ClientScript(UUID_A, WOODCUTTING);
    private static final Target.ClientScript A_DIVINATION = new Target.ClientScript(UUID_A, DIVINATION);
    private static final Target.ClientScript C_WOODCUTTING = new Target.ClientScript(UUID_C, WOODCUTTING);

    private static final List<ConfigField> FIELDS = List.of(
            ConfigField.intField("breakEvery", "Break every (min)", 90),
            ConfigField.intField("breakLength", "Break length (min)", 15),
            ConfigField.boolField("logOut", "Log out during breaks", true));

    @TempDir
    Path dir;

    private GroupStore groups;
    private ManagementTargets targets;
    private ManagementSettingsStore store;
    private ManagementSettings settings;
    private GroupId woodcutters;
    private GroupId questers;

    @BeforeEach
    void newHost() {
        groups = new GroupStore(new GroupsFile(dir.resolve(GroupsFile.FILE_NAME)));
        targets = new ManagementTargets(new ManagementFile(dir.resolve(ManagementFile.FILE_NAME)), groups);
        store = new ManagementSettingsStore(dir.resolve("config"), Runnable::run);
        settings = new ManagementSettings(store, targets, groups);
        woodcutters = group("Woodcutters", UUID_A, UUID_B);
        questers = group("Questers", UUID_A);
        store.saveDefaults(BREAKS, Map.of("breakEvery", "60"));
    }

    private GroupId group(String name, String... members) {
        GroupId id = groups.create(name, Optional.empty()).orElseThrow().id();
        for (String uuid : members) {
            groups.addMember(id, ClientKey.account(uuid));
        }
        return id;
    }

    private static ScriptConfig values(String breakEvery, String breakLength, String logOut) {
        return new ScriptConfig(Map.of("breakEvery", breakEvery, "breakLength", breakLength, "logOut", logOut));
    }

    /** Woodcutters and A's Woodcutting are targets, each with values of its own. */
    private void woodcuttersAndAWoodcutting() {
        targets.add(BREAKS, new Target.Group(woodcutters));
        targets.add(BREAKS, A_WOODCUTTING);
        settings.apply(BREAKS, new Target.Group(woodcutters), values("120", "20", "true"), FIELDS);
        settings.apply(BREAKS, A_WOODCUTTING, values("120", "5", "true"), FIELDS);
    }

    @Nested
    class Precedence {

        @Test
        void aClientScript_takesItsOwnValue_thenItsGroups_thenTheDefaults() {
            woodcuttersAndAWoodcutting();

            TargetView view = settings.view(BREAKS, A_WOODCUTTING, FIELDS);

            assertAll(
                    () -> assertEquals(values("120", "5", "true").asMap(), view.merged().asMap()),
                    () -> assertEquals(Map.of("breakLength", "5"), view.own()),
                    () -> assertEquals(new Inherited("120", new Source.FromGroup(woodcutters)),
                            view.inherited().get("breakEvery")),
                    () -> assertEquals(new Inherited("20", new Source.FromGroup(woodcutters)),
                            view.inherited().get("breakLength")),
                    () -> assertEquals(new Inherited("true", new Source.Defaults()),
                            view.inherited().get("logOut")));
        }

        @Test
        void aGroup_inheritsOnlyFromTheDefaults() {
            woodcuttersAndAWoodcutting();

            TargetView view = settings.view(BREAKS, new Target.Group(woodcutters), FIELDS);

            assertAll(
                    () -> assertEquals(Map.of("breakEvery", "120", "breakLength", "20"), view.own()),
                    () -> assertEquals(new Inherited("60", new Source.Defaults()), view.inherited().get("breakEvery")),
                    () -> assertEquals(new Inherited("15", new Source.Defaults()),
                            view.inherited().get("breakLength")));
        }

        @Test
        void configFor_mergesPerClient_andAClientNoTargetCovers_getsTheDefaults() {
            woodcuttersAndAWoodcutting();

            assertAll(
                    () -> assertEquals(values("120", "5", "true").asMap(),
                            settings.configFor(BREAKS, FIELDS, UUID_A).asMap()),
                    () -> assertEquals(values("120", "20", "true").asMap(),
                            settings.configFor(BREAKS, FIELDS, UUID_B).asMap()),
                    () -> assertEquals(values("60", "15", "true").asMap(),
                            settings.configFor(BREAKS, FIELDS, UUID_C).asMap()));
        }

        @Test
        void configForOneScript_usesOnlyThatScriptsOwnValues() {
            woodcuttersAndAWoodcutting();

            assertAll(
                    () -> assertEquals(values("120", "5", "true").asMap(),
                            settings.configFor(BREAKS, FIELDS, UUID_A, WOODCUTTING).asMap()),
                    () -> assertEquals(values("120", "20", "true").asMap(),
                            settings.configFor(BREAKS, FIELDS, UUID_A, DIVINATION).asMap()));
        }

        @Test
        void twoScriptsOnOneClient_theFirstAddedWinsForTheAccount() {
            targets.add(BREAKS, A_WOODCUTTING);
            targets.add(BREAKS, A_DIVINATION);
            settings.apply(BREAKS, A_WOODCUTTING, values("60", "5", "true"), FIELDS);
            settings.apply(BREAKS, A_DIVINATION, values("60", "7", "false"), FIELDS);

            assertEquals(values("60", "5", "false").asMap(), settings.configFor(BREAKS, FIELDS, UUID_A).asMap());
        }

        @Test
        void aScriptManagingTheWholeHost_givesEveryClientTheDefaults() {
            woodcuttersAndAWoodcutting();
            targets.add(BREAKS, Target.host());

            assertEquals(values("60", "15", "true").asMap(), settings.configFor(BREAKS, FIELDS, UUID_A).asMap());
        }

        @Test
        void theWholeHost_hasNoValuesOfItsOwn() {
            assertThrows(IllegalArgumentException.class,
                    () -> settings.apply(BREAKS, Target.host(), values("1", "2", "false"), FIELDS));
        }
    }

    @Nested
    class GroupNotATarget {

        @Test
        void aGroupTheScriptDoesNotTarget_isSkipped_evenWithValuesSavedForIt() {
            targets.add(BREAKS, A_WOODCUTTING);
            store.saveOverrides(BREAKS, new Target.Group(questers).key(), Map.of("breakLength", "40"));

            TargetView view = settings.view(BREAKS, A_WOODCUTTING, FIELDS);

            assertAll(
                    () -> assertEquals(new Inherited("15", new Source.Defaults()),
                            view.inherited().get("breakLength")),
                    () -> assertEquals("15", settings.configFor(BREAKS, FIELDS, UUID_A).asMap().get("breakLength")));
        }

        @Test
        void aGroupThatStopsBeingATarget_stopsBeingInherited() {
            woodcuttersAndAWoodcutting();

            targets.remove(BREAKS, new Target.Group(woodcutters));

            assertAll(
                    () -> assertEquals(new Inherited("60", new Source.Defaults()),
                            settings.view(BREAKS, A_WOODCUTTING, FIELDS).inherited().get("breakEvery")),
                    () -> assertEquals(values("60", "15", "true").asMap(),
                            settings.configFor(BREAKS, FIELDS, UUID_B).asMap()));
        }
    }

    @Nested
    class OverrideRemoval {

        @Test
        void aValueEqualToTheInheritedOne_storesNoOverride() {
            woodcuttersAndAWoodcutting();
            Path file = dir.resolve("config").resolve(ManagementSettingsStore.BUCKET).resolve("Break%20Scheduler")
                    .resolve("client-" + UUID_A + "-" + WOODCUTTING + ".json");
            assertTrue(Files.isRegularFile(file), "A's own values are on disk before");

            settings.apply(BREAKS, A_WOODCUTTING, values("120", "20", "true"), FIELDS);

            assertAll(
                    () -> assertEquals(Map.of(), settings.view(BREAKS, A_WOODCUTTING, FIELDS).own()),
                    () -> assertEquals(0, settings.ownCount(BREAKS, A_WOODCUTTING)),
                    () -> assertFalse(Files.exists(file)));
        }

        @Test
        void aGroupValueEqualToTheDefault_storesNoOverride_andTheCountSaysSo() {
            targets.add(BREAKS, new Target.Group(woodcutters));

            settings.apply(BREAKS, new Target.Group(woodcutters), values("60", "25", "true"), FIELDS);

            assertAll(
                    () -> assertEquals(Map.of("breakLength", "25"), store.overrides(BREAKS,
                            new Target.Group(woodcutters).key())),
                    () -> assertEquals(1, settings.ownCount(BREAKS, new Target.Group(woodcutters))));
        }

        @Test
        void aFieldLeftOutOfTheValues_keepsItsOwnValue() {
            woodcuttersAndAWoodcutting();

            settings.apply(BREAKS, A_WOODCUTTING, new ScriptConfig(Map.of("logOut", "false")), FIELDS);

            assertEquals(Map.of("breakLength", "5", "logOut", "false"),
                    settings.view(BREAKS, A_WOODCUTTING, FIELDS).own());
        }
    }
}
