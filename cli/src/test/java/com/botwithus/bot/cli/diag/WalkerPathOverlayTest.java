package com.botwithus.bot.cli.diag;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.impl.GameAPIImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The walker-path switch reaches every client, now and later, in both directions. */
class WalkerPathOverlayTest {

    private static final Duration DELIVERY = Duration.ofSeconds(5);

    @TempDir
    Path home;

    private final HostEventBus bus = new HostEventBus();
    private final List<Connection> connections = new ArrayList<>();

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    void theSetting_isOffByDefault() {
        assertFalse(HostSettings.open(home).get(SettingKeys.DRAW_WALKER_PATH));
    }

    @Test
    void bind_appliesTheCurrentValue_thenFollowsEveryChange() {
        HostSettings settings = HostSettings.open(home);
        GameAPIImpl api = connect("BotWithUs_1");

        new WalkerPathOverlay(settings).bind(context());
        settings.set(SettingKeys.DRAW_WALKER_PATH, true);
        settings.set(SettingKeys.DRAW_WALKER_PATH, false);

        InOrder order = inOrder(api);
        order.verify(api).setDrawWalkerPath(false);
        order.verify(api).setDrawWalkerPath(true);
        order.verify(api).setDrawWalkerPath(false);
    }

    @Test
    void aClientOpenedLater_getsTheCurrentValue() {
        HostSettings settings = HostSettings.open(home);
        settings.set(SettingKeys.DRAW_WALKER_PATH, true);
        new WalkerPathOverlay(settings).bind(context());

        GameAPIImpl later = connect("BotWithUs_2");
        bus.publish(new HostEvent.ClientOpened(new ClientRef("BotWithUs_2"), Instant.now()));

        assertTrue(bus.flush(DELIVERY));
        verify(later).setDrawWalkerPath(true);
    }

    @Test
    void aClientNotYetWired_isSkipped() {
        Connection bare = mock(Connection.class);
        connections.add(bare);

        assertDoesNotThrow(() -> new WalkerPathOverlay(HostSettings.open(home)).bind(context()));
    }

    private CliContext context() {
        CliContext ctx = mock(CliContext.class);
        when(ctx.getConnections()).thenReturn(connections);
        when(ctx.getHostEvents()).thenReturn(bus);
        return ctx;
    }

    private GameAPIImpl connect(String pipe) {
        GameAPIImpl api = mock(GameAPIImpl.class);
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(pipe);
        when(conn.getGameAPI()).thenReturn(api);
        connections.add(conn);
        return api;
    }
}
