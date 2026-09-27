package com.botwithus.bot.cli;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupsFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botwithus.bot.cli.TestContexts.connectIdentified;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Groups wired into the real {@link CliContext}: members are accounts, resolved
 * to whatever connection each account is live on, through the same
 * identification path the host takes. Only the agents are fakes.
 */
class CliContextGroupTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final String UUID_C = "00000000000000000000000000000abc";
    private static final String PIPE_A = "BotWithUs_1001";
    private static final String PIPE_B = "BotWithUs_2002";
    private static final String PIPE_A_RESTARTED = "BotWithUs_1111";
    private static final String PIPE_GONE = "BotWithUs_9009";

    @TempDir
    Path dir;

    private final PrintStream discard = new PrintStream(OutputStream.nullOutputStream());

    private CliContext newContext() {
        return TestContexts.inDirInLine(dir, discard);
    }

    private static GroupId group(CliContext ctx, String name, String... members) {
        GroupId id = ctx.getGroupStore().create(name, Optional.empty()).orElseThrow().id();
        for (String uuid : members) {
            ctx.getGroupStore().addMember(id, ClientKey.account(uuid));
        }
        return id;
    }

    private static List<String> pipes(List<Connection> connections) {
        return connections.stream().map(Connection::getName).toList();
    }

    @Test
    void aGroupsConnections_areItsMembersLiveConnections() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        connectIdentified(ctx, PIPE_B, UUID_B, "Bravo");
        GroupId id = group(ctx, "farm", UUID_A, UUID_C);

        assertEquals(List.of(PIPE_A), pipes(ctx.getGroupConnections("farm")));
        assertEquals(List.of(PIPE_A), pipes(ctx.getGroupConnections(id)));
        assertEquals(List.of(), ctx.getGroupConnections("nope"));
    }

    @Test
    void aMember_isFoundOnTheNewPipe_afterItsGameRestarts() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        group(ctx, "farm", UUID_A);

        ctx.handleConnectionError(PIPE_A);
        assertEquals(List.of(), ctx.getGroupConnections("farm"), "offline, but still a member");
        connectIdentified(ctx, PIPE_A_RESTARTED, UUID_A, "Alpha");

        assertEquals(List.of(PIPE_A_RESTARTED), pipes(ctx.getGroupConnections("farm")));
    }

    @Test
    void aClientInSeveralGroups_isInEachGroupsConnections() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        connectIdentified(ctx, PIPE_B, UUID_B, "Bravo");
        group(ctx, "farm", UUID_A);
        group(ctx, "bosses", UUID_B, UUID_A);

        assertAll(
                () -> assertEquals(List.of(PIPE_A), pipes(ctx.getGroupConnections("farm"))),
                () -> assertEquals(List.of(PIPE_B, PIPE_A), pipes(ctx.getGroupConnections("bosses"))),
                () -> assertEquals(List.of("farm", "bosses"),
                        ctx.getGroupStore().groupsOf(UUID_A).stream().map(ClientGroup::name).toList()));
    }

    @Test
    void aSecondClientOnTheSameAccount_isNotOneOfTheGroupsConnections() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        connectIdentified(ctx, PIPE_B, UUID_A, "Alpha");
        group(ctx, "farm", UUID_A);

        assertEquals(new ClientKey.Account(UUID_A, 2), ctx.clientKeyOf(PIPE_B));
        assertEquals(List.of(PIPE_A), pipes(ctx.getGroupConnections("farm")));
    }

    @Test
    void addingAClientByItsPipe_storesItsAccount_andRefusesAnUnidentifiedOne() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        connectIdentified(ctx, PIPE_B, "dev_uuid", "Dev");
        group(ctx, "farm");
        ClientManager manager = ctx.getClientManager();

        assertTrue(manager.addToGroup("farm", PIPE_A));
        assertFalse(manager.addToGroup("farm", PIPE_B), "a pipe-keyed client cannot be a member");

        assertEquals(List.of(UUID_A), ctx.findGroup("farm").orElseThrow().members());
        assertEquals(Optional.of(PIPE_A), ctx.getClientManager().getGroupMembers("farm").stream().findFirst());
    }

    @Test
    void aNameKeyedFile_isMigratedAgainstTheClientsConnectedNow() throws IOException {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        String legacy = "{\"farm\": {\"description\": \"Skillers\", \"members\": [\"%s\", \"%s\"]}}"
                .formatted(PIPE_A, PIPE_GONE);
        Files.writeString(dir.resolve(GroupsFile.FILE_NAME), legacy);

        ctx.loadGroups();

        ClientGroup farm = ctx.findGroup("farm").orElseThrow();
        assertAll(
                () -> assertEquals(List.of(UUID_A), farm.members()),
                () -> assertEquals(List.of(PIPE_GONE), farm.unresolved()),
                () -> assertEquals(Optional.of("Skillers"), farm.description()),
                () -> assertEquals(legacy, Files.readString(dir.resolve(GroupsFile.LEGACY_BACKUP_NAME))),
                () -> assertEquals(List.of(PIPE_A), pipes(ctx.getGroupConnections("farm"))));
    }

    @Test
    void anUnresolvedMember_becomesItsAccount_whenAClientOnItsPipeIsIdentified() throws IOException {
        Files.writeString(dir.resolve(GroupsFile.FILE_NAME),
                "{\"farm\": {\"members\": [\"%s\"]}}".formatted(PIPE_B));
        CliContext ctx = newContext();
        ctx.loadGroups();
        assertEquals(List.of(PIPE_B), ctx.findGroup("farm").orElseThrow().unresolved());

        connectIdentified(ctx, PIPE_B, UUID_B, "Bravo");

        ClientGroup farm = ctx.findGroup("farm").orElseThrow();
        assertEquals(List.of(UUID_B), farm.members());
        assertEquals(List.of(), farm.unresolved());
        CliContext restarted = newContext();
        restarted.loadGroups();
        assertEquals(List.of(UUID_B), restarted.findGroup("farm").orElseThrow().members(), "and it is saved");
    }

    @Test
    void aDescriptionGivenThroughTheOrchestrator_survivesARestart() {
        CliContext ctx = newContext();
        assertTrue(ctx.getClientManager().createGroup("skillers", "Skilling accounts"));

        CliContext restarted = newContext();
        restarted.loadGroups();

        assertEquals("Skilling accounts", restarted.getClientManager().getGroupDescription("skillers"));
    }

    @Test
    void membersSurviveARestart_andAreDescribedByTheirRememberedName() {
        CliContext ctx = newContext();
        connectIdentified(ctx, PIPE_A, UUID_A, "Alpha");
        group(ctx, "farm", UUID_A);
        ctx.handleConnectionError(PIPE_A);
        ctx.saveClients();

        CliContext restarted = newContext();
        restarted.loadGroups();
        restarted.loadClients();

        assertEquals(List.of(UUID_A), restarted.findGroup("farm").orElseThrow().members());
        assertEquals("Alpha (" + UUID_A + ")", restarted.describeAccount(UUID_A));
        assertEquals(UUID_C, restarted.describeAccount(UUID_C));
    }
}
