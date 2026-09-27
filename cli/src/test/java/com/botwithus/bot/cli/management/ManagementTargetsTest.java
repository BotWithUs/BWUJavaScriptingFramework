package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.script.ManagementTarget;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.management.ManagementTargets.ManagedBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real targets over a real {@code management.json} and {@code groups.json} in a temporary folder. */
class ManagementTargetsTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String BREAKS = "Break Scheduler";
    private static final String MONITOR = "Fleet Monitor";
    private static final String WOODCUTTING = "Woodcutting";
    private static final Target.ClientScript B_WOODCUTTING = new Target.ClientScript(UUID_B, WOODCUTTING);

    @TempDir
    Path dir;

    private GroupStore groups;

    @BeforeEach
    void newHost() {
        groups = newGroups();
    }

    private GroupStore newGroups() {
        return new GroupStore(new GroupsFile(dir.resolve(GroupsFile.FILE_NAME)));
    }

    private ManagementTargets targets() {
        return new ManagementTargets(new ManagementFile(dir.resolve(ManagementFile.FILE_NAME)), groups);
    }

    /** The groups and targets as the host's next run would load them. */
    private ManagementTargets reloaded() {
        groups = newGroups();
        groups.load(pipe -> Optional.empty());
        return targets();
    }

    private GroupId group(String name, String... members) {
        GroupId id = groups.create(name, Optional.empty()).orElseThrow().id();
        for (String uuid : members) {
            groups.addMember(id, ClientKey.account(uuid));
        }
        return id;
    }

    private Optional<ManagerSlot> managerOf(GroupId id) {
        return groups.get(id).flatMap(ClientGroup::manager);
    }

    @Nested
    class FirstRun {

        @Test
        void theScriptsTheFirstLoadFinds_manageTheWholeHost_andLaterOnesStartNotApplied() {
            ManagementTargets targets = targets();

            targets.recordLoaded(List.of(BREAKS, MONITOR));
            targets.recordLoaded(List.of("Newcomer"));

            assertAll(
                    () -> assertEquals(List.of(Target.host()), targets.targetsOf(BREAKS)),
                    () -> assertEquals(List.of(Target.host()), targets.targetsOf(MONITOR)),
                    () -> assertTrue(targets.isNotApplied("Newcomer")));
        }

        @Test
        void theMigration_isSaved_soTheNextRunDoesNotRepeatIt() {
            targets().recordLoaded(List.of(BREAKS));

            ManagementTargets next = reloaded();
            next.recordLoaded(List.of(BREAKS, "Newcomer"));

            assertAll(
                    () -> assertEquals(List.of(Target.host()), next.targetsOf(BREAKS)),
                    () -> assertTrue(next.isNotApplied("Newcomer")));
        }

        @Test
        void aChangeMadeBeforeTheFirstLoad_doesNotCancelTheMigration() {
            targets().setDesiredRunning(MONITOR, true);

            ManagementTargets next = reloaded();
            next.recordLoaded(List.of(BREAKS));

            assertEquals(List.of(Target.host()), next.targetsOf(BREAKS));
        }

        @Test
        void aScriptThatAlreadyHasTargets_keepsThem() {
            ManagementTargets targets = targets();
            targets.add(BREAKS, B_WOODCUTTING);

            targets.recordLoaded(List.of(BREAKS));

            assertEquals(List.of(B_WOODCUTTING), targets.targetsOf(BREAKS));
        }

        @Test
        void anUnreadableFile_isSetAside_andTreatedAsAFirstRun() throws Exception {
            Path file = dir.resolve(ManagementFile.FILE_NAME);
            Files.writeString(file, "{ not json");

            ManagementTargets targets = targets();
            targets.recordLoaded(List.of(BREAKS));

            assertAll(
                    () -> assertEquals(List.of(Target.host()), targets.targetsOf(BREAKS)),
                    () -> assertTrue(Files.exists(dir.resolve(ManagementFile.FILE_NAME + ".corrupt"))));
        }
    }

    @Nested
    class WholeHost {

        @Test
        void addingTheWholeHost_clearsEveryOtherTarget_andTheGroupsManagerSlot() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            targets.add(BREAKS, new Target.Group(woodcutters));
            targets.add(BREAKS, B_WOODCUTTING);

            assertTrue(targets.add(BREAKS, Target.host()));

            assertAll(
                    () -> assertEquals(List.of(Target.host()), targets.targetsOf(BREAKS)),
                    () -> assertEquals(Optional.empty(), managerOf(woodcutters)));
        }

        @Test
        void addingAnythingElse_replacesTheWholeHost() {
            ManagementTargets targets = targets();
            targets.add(BREAKS, Target.host());

            targets.add(BREAKS, B_WOODCUTTING);

            assertEquals(List.of(B_WOODCUTTING), targets.targetsOf(BREAKS));
        }

        @Test
        void addingItTwice_changesNothing() {
            ManagementTargets targets = targets();
            targets.add(BREAKS, Target.host());

            assertFalse(targets.add(BREAKS, Target.host()));
        }
    }

    @Nested
    class Groups {

        @Test
        void aGroupTarget_isTheGroupsManagerSlot_inBothDirections() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            GroupId fishers = group("Fishers", UUID_B);

            targets.add(BREAKS, new Target.Group(woodcutters));
            groups.setManager(fishers, Optional.of(new ManagerSlot(BREAKS, true)));

            assertAll(
                    () -> assertEquals(Optional.of(new ManagerSlot(BREAKS, true)), managerOf(woodcutters)),
                    () -> assertEquals(List.of(new Target.Group(woodcutters), new Target.Group(fishers)),
                            targets.targetsOf(BREAKS)));
        }

        @Test
        void removingAGroupTarget_clearsTheSlot_andClearingTheSlotRemovesTheTarget() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            GroupId fishers = group("Fishers", UUID_B);
            targets.add(BREAKS, new Target.Group(woodcutters));
            targets.add(BREAKS, new Target.Group(fishers));

            targets.remove(BREAKS, new Target.Group(woodcutters));
            groups.setManager(fishers, Optional.empty());

            assertAll(
                    () -> assertEquals(Optional.empty(), managerOf(woodcutters)),
                    () -> assertTrue(targets.isNotApplied(BREAKS)));
        }

        @Test
        void aGroupHasOneManager_soAssigningAnotherMovesTheTarget() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            targets.add(BREAKS, new Target.Group(woodcutters));

            targets.add(MONITOR, new Target.Group(woodcutters));

            assertAll(
                    () -> assertTrue(targets.isNotApplied(BREAKS)),
                    () -> assertEquals(List.of(new Target.Group(woodcutters)), targets.targetsOf(MONITOR)));
        }

        @Test
        void aRename_keepsTheTarget_andScriptsSeeTheNewName() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            targets.add(BREAKS, new Target.Group(woodcutters));

            groups.rename(woodcutters, "Lumberjacks");

            assertAll(
                    () -> assertEquals(List.of(new Target.Group(woodcutters)), targets.targetsOf(BREAKS)),
                    () -> assertTrue(targets.scopeOf(BREAKS).seesGroup("Lumberjacks")),
                    () -> assertFalse(targets.scopeOf(BREAKS).seesGroup("Woodcutters")),
                    () -> assertEquals(Set.of(new ManagementTarget.Group(woodcutters.toString(), "Lumberjacks")),
                            targets.apiTargetsOf(BREAKS)));
        }

        @Test
        void aGroupThatDoesNotExist_cannotBeAdded() {
            assertFalse(targets().add(BREAKS, new Target.Group(GroupId.random())));
        }

        @Test
        void pausingTheManager_isKeptOnTheSlot_andResumingUndoesIt() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            targets.add(BREAKS, new Target.Group(woodcutters));

            assertTrue(targets.setManagerPaused(woodcutters, true));
            boolean isPausedOnA = targets.scopeOf(BREAKS).isPausedOn(Optional.of(UUID_A));
            assertTrue(targets.setManagerPaused(woodcutters, false));

            assertAll(
                    () -> assertTrue(isPausedOnA),
                    () -> assertFalse(targets.scopeOf(BREAKS).isPausedOn(Optional.of(UUID_A))),
                    () -> assertFalse(targets.setManagerPaused(group("Unmanaged"), true)));
        }
    }

    @Nested
    class Persistence {

        @Test
        void everyKindOfTarget_andTheWantedState_surviveARestart() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_A);
            targets.add(BREAKS, new Target.Group(woodcutters));
            targets.add(BREAKS, B_WOODCUTTING);
            targets.setDesiredRunning(BREAKS, true);
            targets.add(MONITOR, Target.host());

            ManagementTargets next = reloaded();

            assertAll(
                    () -> assertEquals(List.of(new Target.Group(woodcutters), B_WOODCUTTING),
                            next.targetsOf(BREAKS)),
                    () -> assertTrue(next.isDesiredRunning(BREAKS)),
                    () -> assertEquals(List.of(Target.host()), next.targetsOf(MONITOR)),
                    () -> assertFalse(next.isDesiredRunning(MONITOR)));
        }

        @Test
        void removingATarget_isSaved() {
            ManagementTargets targets = targets();
            targets.add(BREAKS, B_WOODCUTTING);

            targets.remove(BREAKS, B_WOODCUTTING);

            assertTrue(reloaded().isNotApplied(BREAKS));
        }
    }

    @Nested
    class Coverage {

        @Test
        void managedBy_listsTheMostSpecificTargetFirst() {
            ManagementTargets targets = targets();
            GroupId woodcutters = group("Woodcutters", UUID_B);
            targets.add(BREAKS, B_WOODCUTTING);
            targets.add(MONITOR, new Target.Group(woodcutters));
            targets.add("Watcher", Target.host());
            targets.add("Elsewhere", new Target.ClientScript(UUID_A, WOODCUTTING));

            assertEquals(List.of(
                            new ManagedBy(BREAKS, B_WOODCUTTING),
                            new ManagedBy(MONITOR, new Target.Group(woodcutters)),
                            new ManagedBy("Watcher", Target.host())),
                    targets.managedBy(UUID_B, WOODCUTTING));
        }

        @Test
        void aClientScriptTarget_coversThatScriptOnly() {
            ManagementTargets targets = targets();
            targets.add(BREAKS, B_WOODCUTTING);
            Scope scope = targets.scopeOf(BREAKS);

            assertAll(
                    () -> assertTrue(scope.covers(Optional.of(UUID_B), WOODCUTTING)),
                    () -> assertFalse(scope.covers(Optional.of(UUID_B), "Fishing")),
                    () -> assertFalse(scope.covers(Optional.of(UUID_A), WOODCUTTING)),
                    () -> assertTrue(scope.coversClient(Optional.of(UUID_B))),
                    () -> assertFalse(scope.coversClient(Optional.empty())));
        }
    }
}
