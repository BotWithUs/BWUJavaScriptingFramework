package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.events.ClientKey;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real store over a real file in a temporary folder. */
class GroupStoreTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String PIPE_GONE = "BotWithUs_2002";
    private static final Function<String, Optional<String>> NOTHING_LIVE = pipe -> Optional.empty();

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(GroupsFile.FILE_NAME);
    }

    private GroupStore store() {
        return new GroupStore(new GroupsFile(file()));
    }

    /** A second store over the same file, as the host's next run would load it. */
    private GroupStore reloaded() {
        GroupStore store = store();
        store.load(NOTHING_LIVE);
        return store;
    }

    private static ClientGroup only(GroupStore store) {
        assertEquals(1, store.all().size(), () -> "expected one group: " + store.all());
        return store.all().getFirst();
    }

    @Nested
    class Editing {

        @Test
        void aGroupsDescription_isSaved() {
            store().create("Skillers", Optional.of("Skilling accounts"));

            assertEquals(Optional.of("Skilling accounts"), only(reloaded()).description());
        }

        @Test
        void aNameCanBeUsedOnce() {
            GroupStore store = store();
            assertTrue(store.create("Farm", Optional.empty()).isPresent());

            assertTrue(store.create("Farm", Optional.of("again")).isEmpty());
            assertTrue(store.create("  ", Optional.empty()).isEmpty());
            assertEquals(1, store.all().size());
        }

        @Test
        void renaming_keepsTheId_andIsSaved() {
            GroupStore store = store();
            GroupId id = store.create("Farm", Optional.empty()).orElseThrow().id();
            store.create("Bosses", Optional.empty());

            assertFalse(store.rename(id, "Bosses"), "a taken name");
            assertTrue(store.rename(id, "Woodcutters"));

            assertEquals(Optional.of("Woodcutters"), reloaded().get(id).map(ClientGroup::name));
        }

        @Test
        void aClientCanBeInSeveralGroups() {
            GroupStore store = store();
            GroupId farm = store.create("Farm", Optional.empty()).orElseThrow().id();
            GroupId bosses = store.create("Bosses", Optional.empty()).orElseThrow().id();

            store.addMember(farm, ClientKey.account(UUID_A));
            store.addMember(bosses, ClientKey.account(UUID_A));
            store.addMember(bosses, ClientKey.account(UUID_B));

            GroupStore next = reloaded();
            assertEquals(List.of("Farm", "Bosses"), next.groupsOf(UUID_A).stream().map(ClientGroup::name).toList());
            assertEquals(List.of("Bosses"), next.groupsOf(UUID_B).stream().map(ClientGroup::name).toList());
        }

        @Test
        void addingAMemberTwice_changesNothing() {
            GroupStore store = store();
            GroupId id = store.create("Farm", Optional.empty()).orElseThrow().id();
            store.addMember(id, ClientKey.account(UUID_A));

            assertEquals(new MemberChange.AlreadyMember(), store.addMember(id, ClientKey.account(UUID_A)));
            assertEquals(List.of(UUID_A), store.get(id).orElseThrow().members());
        }

        @Test
        void aClientKeyedByItsPipe_isRefused_withTheReason() {
            GroupStore store = store();
            GroupId id = store.create("Farm", Optional.empty()).orElseThrow().id();

            MemberChange change = store.addMember(id, ClientKey.pipe(PIPE_A));

            String reason = refusalReason(change);
            assertTrue(reason.contains(PIPE_A) && reason.contains("no account UUID"), reason);
            assertTrue(store.get(id).orElseThrow().members().isEmpty());
        }

        @Test
        void aSecondClientOnOneAccount_isRefused_withTheReason() {
            GroupStore store = store();
            GroupId id = store.create("Farm", Optional.empty()).orElseThrow().id();

            MemberChange change = store.addMember(id, new ClientKey.Account(UUID_A, 2));

            String reason = refusalReason(change);
            assertTrue(reason.contains(UUID_A) && reason.contains("second client"), reason);
            assertTrue(store.get(id).orElseThrow().members().isEmpty());
        }

        @Test
        void aManagerIsSaved_andCleared() {
            GroupStore store = store();
            GroupId id = store.create("Farm", Optional.empty()).orElseThrow().id();

            store.setManager(id, Optional.of(new ManagerSlot("Break Scheduler", true)));
            assertEquals(Optional.of(new ManagerSlot("Break Scheduler", true)),
                    reloaded().get(id).orElseThrow().manager());

            store.setManager(id, Optional.empty());
            assertEquals(Optional.empty(), reloaded().get(id).orElseThrow().manager());
        }

        @Test
        void deleting_isSaved() {
            GroupStore store = store();
            GroupId id = store.create("Farm", Optional.empty()).orElseThrow().id();

            assertTrue(store.delete(id));
            assertFalse(store.delete(id));
            assertTrue(reloaded().all().isEmpty());
        }

        private static String refusalReason(MemberChange change) {
            return switch (change) {
                case MemberChange.Refused refused -> refused.reason();
                case MemberChange.Added _, MemberChange.AlreadyMember _, MemberChange.NoSuchGroup _ ->
                        throw new AssertionError("expected a refusal, got " + change);
            };
        }
    }

    @Nested
    class MigratingANameKeyedFile {

        private static final String LEGACY = """
                {
                  "farm": {
                    "description": "Skillers",
                    "members": ["%s", "%s"]
                  },
                  "empty": {
                    "members": []
                  }
                }""".formatted(PIPE_A, PIPE_GONE);

        private final Function<String, Optional<String>> aLiveOnPipeA =
                pipe -> Optional.ofNullable(Map.of(PIPE_A, UUID_A).get(pipe));

        @Test
        void aMemberOnALivePipe_becomesItsAccount_andTheRestStayUnresolved() throws IOException {
            Files.writeString(file(), LEGACY);
            GroupStore store = store();

            store.load(aLiveOnPipeA);

            ClientGroup farm = store.byName("farm").orElseThrow();
            assertAll(
                    () -> assertEquals(List.of(UUID_A), farm.members()),
                    () -> assertEquals(List.of(PIPE_GONE), farm.unresolved()),
                    () -> assertEquals(Optional.of("Skillers"), farm.description()),
                    () -> assertEquals(List.of("farm", "empty"), store.all().stream().map(ClientGroup::name).toList()));
        }

        @Test
        void theOldFileIsBackedUp_andReplacedByTheCurrentFormat() throws IOException {
            Files.writeString(file(), LEGACY);
            GroupStore store = store();

            store.load(aLiveOnPipeA);

            assertEquals(LEGACY, Files.readString(dir.resolve(GroupsFile.LEGACY_BACKUP_NAME)));
            assertEquals(new GroupsFile.Contents.Current(store.all()), new GroupsFile(file()).read());
        }

        @Test
        void loadingAgain_changesNothing() throws IOException {
            Files.writeString(file(), LEGACY);
            GroupStore first = store();
            first.load(aLiveOnPipeA);
            String migrated = Files.readString(file());

            GroupStore second = store();
            second.load(aLiveOnPipeA);

            assertEquals(first.all(), second.all());
            assertEquals(migrated, Files.readString(file()));
        }

        @Test
        void migratingTheSameFileTwice_givesTheSameIds() throws IOException {
            Files.writeString(file(), LEGACY);
            GroupStore first = store();
            first.load(aLiveOnPipeA);
            Files.writeString(file(), LEGACY);

            GroupStore again = store();
            again.load(aLiveOnPipeA);

            assertEquals(first.all(), again.all());
        }

        @Test
        void anUnresolvedMember_isResolvedWhenAClientOnItsPipeIsIdentified() throws IOException {
            Files.writeString(file(), LEGACY);
            GroupStore store = store();
            store.load(NOTHING_LIVE);
            assertTrue(store.isUnresolved(PIPE_GONE));

            assertEquals(1, store.resolve(PIPE_GONE, UUID_B));

            ClientGroup farm = reloaded().byName("farm").orElseThrow();
            assertEquals(List.of(PIPE_A), farm.unresolved(), "only the identified pipe is resolved");
            assertTrue(farm.contains(UUID_B));
            assertFalse(store.isUnresolved(PIPE_GONE));
        }

        @Test
        void anUnresolvedMember_canBeRemoved() throws IOException {
            Files.writeString(file(), LEGACY);
            GroupStore store = store();
            store.load(aLiveOnPipeA);
            GroupId farm = store.byName("farm").orElseThrow().id();

            assertTrue(store.removeUnresolved(farm, PIPE_GONE));

            assertEquals(List.of(), reloaded().get(farm).orElseThrow().unresolved());
        }
    }
}
