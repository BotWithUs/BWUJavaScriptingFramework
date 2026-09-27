package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.ConnectionStatusTracker;
import com.botwithus.bot.cli.FakeAgent;
import com.botwithus.bot.core.sdn.InstalledScriptsLedger;
import com.botwithus.bot.core.sdn.SdnCatalogueRefresher;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnInstaller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.OptionalInt;

import static com.botwithus.bot.cli.FakeAgent.GET_ACCOUNT_INFO;
import static com.botwithus.bot.cli.FakeAgent.accountInfo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The card's world comes from {@code get_current_world}. {@code get_account_info}
 * never carries a world, which is why reading one from the account reply showed
 * no world on every card.
 */
class LiveClientBoardWorldTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final int IN_GAME = 30;
    private static final int LOBBY = 20;
    private static final int WORLD = 301;
    private static final double MID_JITTER = 0.5;

    @TempDir
    Path tempDir;

    @Test
    void clients_showTheWorldTheClientIsIn_andNoneOutsideOne() {
        FakeAgent agent = new FakeAgent()
                .reply(GET_ACCOUNT_INFO, accountInfo("Zezima", "", UUID, IN_GAME, true))
                .world(WORLD);
        Connection conn = agent.connection("BotWithUs_1");
        ConnectionStatusTracker tracker = new ConnectionStatusTracker(Runnable::run, Duration.ofDays(1));
        tracker.refresh(conn);
        BoardRegistry registry = new BoardRegistry(Clock.systemUTC());
        registry.connect(conn, UUID, "Zezima");
        CliContext ctx = mock(CliContext.class);
        when(ctx.getConnections()).thenReturn(List.of(conn));
        when(ctx.getClientRegistry()).thenReturn(registry.registry);
        LiveClientBoard board = board(ctx);

        assertEquals(OptionalInt.of(WORLD), board.clients().getFirst().world());

        agent.reply(GET_ACCOUNT_INFO, accountInfo("Zezima", "", UUID, LOBBY, false));
        tracker.refresh(conn);
        assertEquals(OptionalInt.empty(), board.clients().getFirst().world());
    }

    private LiveClientBoard board(CliContext ctx) {
        SdnCatalogueRefresher catalogue = new SdnCatalogueRefresher(
                () -> new SdnCatalogueResult.Delivered(List.of(), false), Runnable::run,
                InstantSource.system(), () -> MID_JITTER);
        return new LiveClientBoard(ctx, id -> { }, Clock.systemUTC(), catalogue,
                new SdnInstaller(tempDir, new InstalledScriptsLedger(tempDir, InstantSource.system())),
                task -> { });
    }
}
