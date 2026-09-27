package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.TestContexts;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupsFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroupCommandTest {

    private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    private GroupCommand command;
    private CliContext ctx;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        command = new GroupCommand();
        output = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(output);
        ctx = TestContexts.inDir(tempDir, ps);
    }

    private ParsedCommand parse(String input) {
        return CommandParser.parse(input);
    }

    private String output() {
        return output.toString();
    }

    @Test
    void nameAndAliases() {
        assertEquals("group", command.name());
        assertTrue(command.aliases().contains("g"));
    }

    @Test
    void noSubcommandShowsUsage() {
        command.execute(parse("group"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void createGroup() {
        command.execute(parse("group create farm"), ctx);
        assertTrue(ctx.findGroup("farm").isPresent());
        assertTrue(output().contains("Group 'farm' created."));
    }

    @Test
    void createDuplicateGroup() {
        TestContexts.createGroup(ctx, "farm");
        command.execute(parse("group create farm"), ctx);
        assertTrue(output().contains("already exists"));
    }

    @Test
    void createGroupNoName() {
        command.execute(parse("group create"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void deleteGroup() {
        TestContexts.createGroup(ctx, "farm");
        command.execute(parse("group delete farm"), ctx);
        assertTrue(ctx.findGroup("farm").isEmpty());
        assertTrue(output().contains("deleted"));
    }

    @Test
    void deleteNonExistent() {
        command.execute(parse("group delete nope"), ctx);
        assertTrue(output().contains("not found"));
    }

    @Test
    void addToGroup() {
        TestContexts.createGroup(ctx, "farm");
        command.execute(parse("group add farm BotWithUs"), ctx);
        assertTrue(ctx.findGroup("farm").orElseThrow().contains("BotWithUs"));
        assertTrue(output().contains("Added"));
    }

    @Test
    void addDuplicateToGroup() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "BotWithUs");
        command.execute(parse("group add farm BotWithUs"), ctx);
        assertTrue(output().contains("already in group"));
    }

    @Test
    void addToNonExistentGroup() {
        command.execute(parse("group add nope BotWithUs"), ctx);
        assertTrue(output().contains("not found"));
    }

    @Test
    void addMissingArgs() {
        command.execute(parse("group add"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void removeFromGroup() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "BotWithUs");
        command.execute(parse("group remove farm BotWithUs"), ctx);
        assertFalse(ctx.findGroup("farm").orElseThrow().contains("BotWithUs"));
        assertTrue(output().contains("Removed"));
    }

    @Test
    void removeNonMember() {
        TestContexts.createGroup(ctx, "farm");
        command.execute(parse("group remove farm BotWithUs"), ctx);
        assertTrue(output().contains("not in group"));
    }

    @Test
    void listGroupsEmpty() {
        command.execute(parse("group list"), ctx);
        assertTrue(output().contains("No groups defined"));
    }

    @Test
    void listGroupsWithMembers() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", "Bot1");
        TestContexts.addMember(ctx, "farm", "Bot2");
        command.execute(parse("group list"), ctx);
        String out = output();
        assertTrue(out.contains("farm"));
        assertTrue(out.contains("Bot1"));
        assertTrue(out.contains("Bot2"));
    }

    @Test
    void listGroupsEmpty_showsEmptyMarker() {
        TestContexts.createGroup(ctx, "farm");
        command.execute(parse("group list"), ctx);
        assertTrue(output().contains("(empty)"));
    }

    @Test
    void infoNonExistent() {
        command.execute(parse("group info nope"), ctx);
        assertTrue(output().contains("not found"));
    }

    @Test
    void infoEmptyGroup() {
        TestContexts.createGroup(ctx, "farm");
        command.execute(parse("group info farm"), ctx);
        assertTrue(output().contains("no members"));
    }

    @Test
    void addingAConnectedClientByItsPipe_addsItsAccount() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.connectIdentified(ctx, "BotWithUs_1001", ACCOUNT, "Alpha");

        command.execute(parse("group add farm BotWithUs_1001"), ctx);

        assertEquals(List.of(ACCOUNT), ctx.findGroup("farm").orElseThrow().members());
        command.execute(parse("group info farm"), ctx);
        assertTrue(output().contains("Alpha (" + ACCOUNT + ") [") && output().contains("connected"), output());
    }

    @Test
    void addingAClientWithNoAccount_isRefused_withTheReason() {
        TestContexts.createGroup(ctx, "farm");
        TestContexts.connectIdentified(ctx, "BotWithUs_1001", "dev_uuid", "Dev");

        command.execute(parse("group add farm BotWithUs_1001"), ctx);

        assertTrue(output().contains("Cannot add 'BotWithUs_1001'") && output().contains("no account UUID"),
                output());
        assertTrue(ctx.findGroup("farm").orElseThrow().members().isEmpty());
    }

    @Test
    void anUnresolvedMember_isShownByItsPipe_andCanBeRemoved() throws IOException {
        Files.writeString(tempDir.resolve(GroupsFile.FILE_NAME), "{\"farm\": {\"members\": [\"BotWithUs_9\"]}}");
        ctx.loadGroups();

        command.execute(parse("group info farm"), ctx);
        assertTrue(output().contains("unknown client (pipe BotWithUs_9)"), output());

        command.execute(parse("group remove farm BotWithUs_9"), ctx);
        assertTrue(ctx.findGroup("farm").orElseThrow().unresolved().isEmpty());
    }

    @Test
    void renaming_keepsTheGroup() {
        GroupId id = TestContexts.createGroup(ctx, "farm");
        TestContexts.addMember(ctx, "farm", ACCOUNT);

        command.execute(parse("group rename farm woodcutters"), ctx);

        assertEquals(id, ctx.findGroup("woodcutters").orElseThrow().id());
        assertEquals(List.of(ACCOUNT), ctx.findGroup("woodcutters").orElseThrow().members());
        assertTrue(ctx.findGroup("farm").isEmpty());
    }

    @Test
    void unknownSubcommand() {
        command.execute(parse("group foo"), ctx);
        assertTrue(output().contains("Unknown subcommand"));
    }
}
