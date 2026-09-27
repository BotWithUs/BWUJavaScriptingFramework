package com.botwithus.bot.cli;

import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static com.botwithus.bot.cli.FakeAgent.GET_ACCOUNT_INFO;
import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds a {@link CliContext} whose files live in a folder the test owns, for
 * tests in any package: the constructor that takes the folder is not public.
 * Also connects and identifies clients the way the host does, through the
 * status tracker, with a {@link FakeAgent} on the other end.
 */
public final class TestContexts {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final int IN_GAME = 30;
    private static final int WORLD = 84;

    private TestContexts() {
    }

    /** A context keeping groups, remembered clients and queued starts in {@code dir}. */
    public static CliContext inDir(Path dir, PrintStream out) {
        LogBuffer logBuffer = new LogBuffer();
        return new CliContext(logBuffer, new LogCapture(logBuffer, out, out), dir.resolve(GroupsFile.FILE_NAME));
    }

    /**
     * As {@link #inDir}, with background work run on the thread that asks for
     * it, so its effects are visible once {@link #flush} returns.
     */
    public static CliContext inDirInLine(Path dir, PrintStream out) {
        LogBuffer logBuffer = new LogBuffer();
        return new CliContext(logBuffer, new LogCapture(logBuffer, out, out), dir.resolve(GroupsFile.FILE_NAME),
                Runnable::run);
    }

    /**
     * Registers a live connection on {@code pipe} whose agent reports account
     * {@code uuid} and the name {@code name}, and reads its account as the
     * status tracker does, so the host identifies it.
     */
    public static Connection connectIdentified(CliContext ctx, String pipe, String uuid, String name) {
        return connectIdentified(ctx, pipe, uuid, name, runtime(pipe));
    }

    /** As {@link #connectIdentified(CliContext, String, String, String)}, over {@code runtime}. */
    public static Connection connectIdentified(CliContext ctx, String pipe, String uuid, String name,
                                               ScriptRuntime runtime) {
        Map<String, Object> info = accountInfo(name, name, uuid, IN_GAME, true);
        Connection conn = new FakeAgent().reply(GET_ACCOUNT_INFO, info).world(WORLD).connection(pipe, runtime);
        assertTrue(ctx.registerConnection(conn), "pipe already registered: " + pipe);
        ctx.getStatusTracker().refresh(conn);
        flush(ctx);
        return conn;
    }

    /** Creates an empty group called {@code name}. */
    public static GroupId createGroup(CliContext ctx, String name) {
        return ctx.getGroupStore().create(name, Optional.empty()).orElseThrow().id();
    }

    /** Adds the client on account {@code accountUuid} to the group called {@code group}. */
    public static void addMember(CliContext ctx, String group, String accountUuid) {
        GroupId id = ctx.findGroup(group).orElseThrow().id();
        MemberChange change = ctx.getGroupStore().addMember(id, ClientKey.account(accountUuid));
        boolean isAdded = switch (change) {
            case MemberChange.Added _ -> true;
            case MemberChange.AlreadyMember _, MemberChange.NoSuchGroup _, MemberChange.Refused _ -> false;
        };
        assertTrue(isAdded, () -> "not added: " + change);
    }

    /** Waits until every host event published so far is delivered. */
    public static void flush(CliContext ctx) {
        assertTrue(ctx.getHostEvents().flush(FLUSH), "host events were not delivered");
    }

    /** A script runtime that can start and stop real scripts over a mock script context. */
    public static ScriptRuntime runtime(String pipe) {
        ScriptContext context = mock(ScriptContext.class);
        when(context.getNavigation()).thenReturn(mock(Navigation.class));
        ScriptRuntime runtime = new ScriptRuntime(context);
        runtime.setConnectionName(pipe);
        return runtime;
    }
}
