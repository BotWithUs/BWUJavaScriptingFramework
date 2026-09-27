package com.botwithus.bot.cli;

import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.config.ScriptProfileStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

import static com.botwithus.bot.cli.FakeAgent.GET_ACCOUNT_INFO;
import static com.botwithus.bot.cli.FakeAgent.LOGIN_TO_LOBBY;
import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The auto-start probe of a client that has no display name yet: the pipe
 * scanner connects clients still at the login screen, and the account UUID they
 * already carry must be kept rather than dropped until a name appears.
 *
 * <p>The probe of a named client loads the installed scripts, which reads the
 * user's scripts directory, so that path is covered through
 * {@link ConnectionStatusTrackerTest} instead.</p>
 */
class AutoStartManagerProbeTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final int LOGIN_SCREEN = 10;
    private static final String LOADER_NAME = "Zezima";

    @TempDir
    Path tempDir;

    private HostSettings settings;
    private AutoStartManager manager;

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        CliContext ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve("groups.json"));
        settings = HostSettings.open(tempDir);
        manager = new AutoStartManager(ctx, new ScriptProfileStore(tempDir.resolve(".botwithus")), settings);
    }

    @AfterEach
    void tearDown() {
        settings.close();
    }

    @Test
    void probe_withoutADisplayName_keepsTheUuidAndNudgesTowardTheLobbyOnce() {
        // A newer agent already knows the loader's account name at the login
        // screen. That labels the client but must not count as identifying it.
        FakeAgent agent = new FakeAgent()
                .reply(GET_ACCOUNT_INFO, accountInfo("", LOADER_NAME, UUID, LOGIN_SCREEN, false))
                .reply(LOGIN_TO_LOBBY, Map.of());
        Connection conn = agent.connection("BotWithUs_1");

        manager.probeAndAutoStart(conn);
        manager.probeAndAutoStart(conn);

        assertEquals(UUID, conn.getAccountUuid());
        assertEquals(UUID, conn.getRuntime().getAccountUuid());
        assertEquals(GameState.LOGIN_SCREEN, conn.getGameState());
        assertNull(conn.getAccountName(), "still unidentified, so the scanner keeps re-probing");
        assertEquals(LOADER_NAME, conn.getDisplayName().orElseThrow());
        assertEquals(1, agent.callsTo(LOGIN_TO_LOBBY));
    }
}
