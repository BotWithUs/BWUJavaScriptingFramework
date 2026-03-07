package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.AutoStartManager;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.command.CommandParser;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoStartCommandTest {

    @TempDir
    Path tempDir;

    private AutoStartCommand command;
    private CliContext ctx;
    private ScriptProfileStore profileStore;
    private AutoStartManager autoStartManager;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        output = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(output);
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, ps, ps);
        ctx = new CliContext(logBuffer, logCapture);

        profileStore = new ScriptProfileStore(tempDir.resolve(".botwithus"));
        autoStartManager = new AutoStartManager(ctx, profileStore);
        ctx.setProfileStore(profileStore);
        ctx.setAutoStartManager(autoStartManager);

        command = new AutoStartCommand(profileStore, autoStartManager);
    }

    private ParsedCommand parse(String input) {
        return CommandParser.parse(input);
    }

    private String output() {
        return output.toString();
    }

    /**
     * Creates a spy CliContext with a mock connection that has the given account name.
     */
    private CliContext spyWithAccount(String accountName) {
        output.reset();
        LogBuffer logBuffer = new LogBuffer();
        LogCapture logCapture = new LogCapture(logBuffer, new PrintStream(output), new PrintStream(output));
        CliContext spyCtx = spy(new CliContext(logBuffer, logCapture));
        spyCtx.setProfileStore(profileStore);
        spyCtx.setAutoStartManager(autoStartManager);

        PipeClient pipe = mock(PipeClient.class);
        when(pipe.isOpen()).thenReturn(true);
        RpcClient rpc = mock(RpcClient.class);
        com.botwithus.bot.api.ScriptContext scriptCtx = mock(com.botwithus.bot.api.ScriptContext.class);
        ScriptRuntime runtime = new ScriptRuntime(scriptCtx);
        Connection conn = new Connection("BotWithUs", pipe, rpc, runtime);
        conn.setAccountName(accountName);

        doReturn(conn).when(spyCtx).getActiveConnection();
        doReturn(true).when(spyCtx).hasActiveConnection();
        doReturn("BotWithUs").when(spyCtx).getActiveConnectionName();

        return spyCtx;
    }

    // --- Command metadata ---

    @Test
    void nameAndAliases() {
        assertEquals("autostart", command.name());
        assertTrue(command.aliases().contains("as"));
    }

    // --- List ---

    @Test
    void listEmpty() {
        command.execute(parse("autostart list"), ctx);
        assertTrue(output().contains("No auto-start profiles configured"));
    }

    @Test
    void listShowsAccountProfiles() {
        profileStore.setAccountScripts("Alice", List.of("Script1", "Script2"));
        profileStore.setAccountScripts("Bob", List.of("Script3"));

        command.execute(parse("autostart list"), ctx);
        String out = output();
        assertTrue(out.contains("Alice"));
        assertTrue(out.contains("Bob"));
        assertTrue(out.contains("Script1"));
        assertTrue(out.contains("Script3"));
    }

    @Test
    void listShowsGroupProfiles() {
        profileStore.setGroupScripts("farm1", List.of("WoodcuttingScript"));

        command.execute(parse("autostart list"), ctx);
        String out = output();
        assertTrue(out.contains("farm1"));
        assertTrue(out.contains("WoodcuttingScript"));
    }

    @Test
    void listDefaultSubcommand() {
        command.execute(parse("autostart"), ctx);
        assertTrue(output().contains("No auto-start profiles configured"));
    }

    // --- Add ---

    @Test
    void addWithNoConnection() {
        command.execute(parse("autostart add MyScript"), ctx);
        assertTrue(output().contains("No active connection"));
    }

    @Test
    void addWithNoScriptName() {
        command.execute(parse("autostart add"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void addScript() {
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart add WoodcuttingScript"), spyCtx);
        assertTrue(output().contains("Added"));
        assertTrue(profileStore.getAccountScripts("PlayerOne").contains("WoodcuttingScript"));
    }

    @Test
    void addDuplicateScript() {
        profileStore.setAccountScripts("PlayerOne", List.of("WoodcuttingScript"));
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart add WoodcuttingScript"), spyCtx);
        assertTrue(output().contains("already in auto-start"));
    }

    // --- Remove ---

    @Test
    void removeScript() {
        profileStore.setAccountScripts("PlayerOne", List.of("ScriptA", "ScriptB"));
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart remove ScriptA"), spyCtx);
        assertTrue(output().contains("Removed"));
        assertEquals(List.of("ScriptB"), profileStore.getAccountScripts("PlayerOne"));
    }

    @Test
    void removeNonExistentScript() {
        profileStore.setAccountScripts("PlayerOne", List.of("ScriptA"));
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart remove ScriptX"), spyCtx);
        assertTrue(output().contains("not found in auto-start"));
    }

    @Test
    void removeWithNoScriptName() {
        command.execute(parse("autostart remove"), ctx);
        assertTrue(output().contains("Usage:") || output().contains("No active connection"));
    }

    // --- Enable / Disable ---

    @Test
    void enableAutoStart() {
        profileStore.setAutoStart("PlayerOne", false);
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart enable"), spyCtx);
        assertTrue(output().contains("enabled"));
        assertTrue(profileStore.isAutoStart("PlayerOne"));
    }

    @Test
    void disableAutoStart() {
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart disable"), spyCtx);
        assertTrue(output().contains("disabled"));
        assertFalse(profileStore.isAutoStart("PlayerOne"));
    }

    @Test
    void enableWithNoConnection() {
        command.execute(parse("autostart enable"), ctx);
        assertTrue(output().contains("No active connection"));
    }

    // --- Clear ---

    @Test
    void clearCurrentAccountProfile() {
        profileStore.setAccountScripts("PlayerOne", List.of("Script1"));
        CliContext spyCtx = spyWithAccount("PlayerOne");
        command.execute(parse("autostart clear"), spyCtx);
        assertTrue(output().contains("Cleared"));
        assertTrue(profileStore.getAccountScripts("PlayerOne").isEmpty());
    }

    @Test
    void clearSpecificAccount() {
        profileStore.setAccountScripts("SomePlayer", List.of("Script1"));
        command.execute(parse("autostart clear SomePlayer"), ctx);
        assertTrue(output().contains("Cleared"));
    }

    @Test
    void clearNonExistent() {
        command.execute(parse("autostart clear Nobody"), ctx);
        assertTrue(output().contains("No profile found"));
    }

    // --- Group operations ---

    @Test
    void groupAddScript() {
        command.execute(parse("autostart group farm1 add WoodcuttingScript"), ctx);
        assertTrue(output().contains("Added"));
        assertEquals(List.of("WoodcuttingScript"), profileStore.getGroupScripts("farm1"));
    }

    @Test
    void groupAddDuplicate() {
        profileStore.setGroupScripts("farm1", List.of("WoodcuttingScript"));
        command.execute(parse("autostart group farm1 add WoodcuttingScript"), ctx);
        assertTrue(output().contains("already in group"));
    }

    @Test
    void groupRemoveScript() {
        profileStore.setGroupScripts("farm1", List.of("ScriptA", "ScriptB"));
        command.execute(parse("autostart group farm1 remove ScriptA"), ctx);
        assertTrue(output().contains("Removed"));
        assertEquals(List.of("ScriptB"), profileStore.getGroupScripts("farm1"));
    }

    @Test
    void groupRemoveNonExistent() {
        profileStore.setGroupScripts("farm1", List.of("ScriptA"));
        command.execute(parse("autostart group farm1 remove ScriptX"), ctx);
        assertTrue(output().contains("not in group"));
    }

    @Test
    void groupListScripts() {
        profileStore.setGroupScripts("farm1", List.of("Script1", "Script2"));
        command.execute(parse("autostart group farm1 list"), ctx);
        String out = output();
        assertTrue(out.contains("farm1"));
        assertTrue(out.contains("Script1"));
        assertTrue(out.contains("Script2"));
    }

    @Test
    void groupListEmpty() {
        command.execute(parse("autostart group farm1 list"), ctx);
        assertTrue(output().contains("No scripts in group"));
    }

    @Test
    void groupMissingArgs() {
        command.execute(parse("autostart group"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void groupMissingAction() {
        command.execute(parse("autostart group farm1"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void groupUnknownAction() {
        command.execute(parse("autostart group farm1 fly"), ctx);
        assertTrue(output().contains("Unknown group action"));
    }

    @Test
    void groupAddMissingScriptName() {
        command.execute(parse("autostart group farm1 add"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    @Test
    void groupRemoveMissingScriptName() {
        command.execute(parse("autostart group farm1 remove"), ctx);
        assertTrue(output().contains("Usage:"));
    }

    // --- Settings ---

    @Test
    void showSettings() {
        command.execute(parse("autostart settings"), ctx);
        String out = output();
        assertTrue(out.contains("autoConnect"));
        assertTrue(out.contains("pipePrefix"));
        assertTrue(out.contains("probeLobby"));
        assertTrue(out.contains("scanInterval"));
    }

    // --- On / Off ---

    @Test
    void onEnablesAutoConnect() {
        command.execute(parse("autostart on"), ctx);
        assertTrue(output().contains("Auto-connect enabled"));
        assertTrue(profileStore.isAutoConnect());
    }

    @Test
    void offDisablesAutoConnect() {
        profileStore.setAutoConnect(true);
        command.execute(parse("autostart off"), ctx);
        assertTrue(output().contains("Auto-connect disabled"));
        assertFalse(profileStore.isAutoConnect());
    }

    // --- Unknown subcommand ---

    @Test
    void unknownSubcommand() {
        command.execute(parse("autostart xyz"), ctx);
        assertTrue(output().contains("Unknown subcommand"));
    }
}
