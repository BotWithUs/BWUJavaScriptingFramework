package com.botwithus.bot.cli;

import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Settings the host applies to every connection: the RPC timeout
 * ({@code defaultTimeout}) and the stall threshold ({@code scripts.stallAfterMs}).
 * Drives the real {@link CliContext}, {@link HostSettings}, {@link RpcClient} and
 * {@link ScriptRuntime}; only the pipe is a mock.
 */
class CliContextSettingsTest {

    private static final long CUSTOM_TIMEOUT_MS = 2_500L;
    private static final long CHANGED_TIMEOUT_MS = 4_000L;
    private static final long CUSTOM_STALL_MS = 30_000L;
    private static final long CHANGED_STALL_MS = 90_000L;

    @TempDir
    Path tempDir;

    private CliContext ctx;
    private HostSettings settings;
    private final List<RpcClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve("groups.json"));
        settings = HostSettings.open(tempDir);
        ctx.setSettings(settings);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(RpcClient::close);
        settings.close();
    }

    private Connection connection(String name) {
        RpcClient rpc = new RpcClient(mock(PipeClient.class));
        clients.add(rpc);
        ScriptRuntime runtime = new ScriptRuntime(mock(ScriptContext.class));
        return new Connection(name, mock(PipeClient.class), rpc, runtime, new ScriptManagerImpl(runtime));
    }

    @Test
    void aNewConnectionTakesItsRpcTimeoutFromTheSettings() {
        settings.set(SettingKeys.RPC_TIMEOUT_MS, CUSTOM_TIMEOUT_MS);
        Connection conn = connection("BotWithUs_1");

        ctx.registerConnection(conn);

        assertEquals(CUSTOM_TIMEOUT_MS, conn.getRpc().getTimeout());
    }

    @Test
    void changingTheRpcTimeoutAppliesToConnectionsAlreadyOpen() {
        Connection conn = connection("BotWithUs_1");
        ctx.registerConnection(conn);

        settings.set(SettingKeys.RPC_TIMEOUT_MS, CHANGED_TIMEOUT_MS);

        assertEquals(CHANGED_TIMEOUT_MS, conn.getRpc().getTimeout());
    }

    @Test
    void aConnectionsRuntimeMarksStallsAfterTheSettingAndFollowsChanges() {
        settings.set(SettingKeys.STALL_AFTER_MS, CUSTOM_STALL_MS);
        Connection conn = connection("BotWithUs_1");
        ctx.registerConnection(conn);

        assertEquals(CUSTOM_STALL_MS, conn.getRuntime().stallThresholdMs());
        settings.set(SettingKeys.STALL_AFTER_MS, CHANGED_STALL_MS);
        assertEquals(CHANGED_STALL_MS, conn.getRuntime().stallThresholdMs());
    }

    @Test
    void managementScriptsMarkStallsAfterTheSettingToo() {
        ctx.initManagementRuntime();

        settings.set(SettingKeys.STALL_AFTER_MS, CUSTOM_STALL_MS);

        assertEquals(CUSTOM_STALL_MS, ctx.getManagementRuntime().stallThresholdMs());
    }
}
