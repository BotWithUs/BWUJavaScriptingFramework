package com.botwithus.bot.cli.diag;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.settings.FreeToPlayRouting;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The free-to-play routing setting is off by default and reaches every client, now and later. */
class FreeToPlayRoutingSwitchTest {

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
        assertEquals(FreeToPlayRouting.OFF, HostSettings.open(home).get(SettingKeys.WALK_FREE_TO_PLAY));
    }

    @Test
    void byDefault_noClientIsEverTurnedOn() {
        GameAPIImpl api = connect("BotWithUs_1");

        new FreeToPlayRoutingSwitch(HostSettings.open(home)).bind(context());

        verify(api).setRestrictFreeToPlay(false);
        verify(api, never()).setRestrictFreeToPlay(true);
    }

    @Test
    void bind_appliesTheCurrentValue_thenFollowsEveryChange() {
        HostSettings settings = HostSettings.open(home);
        GameAPIImpl api = connect("BotWithUs_1");

        new FreeToPlayRoutingSwitch(settings).bind(context());
        settings.set(SettingKeys.WALK_FREE_TO_PLAY, FreeToPlayRouting.RESTRICT_WHEN_FREE_TO_PLAY);
        settings.set(SettingKeys.WALK_FREE_TO_PLAY, FreeToPlayRouting.OFF);

        InOrder order = inOrder(api);
        order.verify(api).setRestrictFreeToPlay(false);
        order.verify(api).setRestrictFreeToPlay(true);
        order.verify(api).setRestrictFreeToPlay(false);
    }

    @Test
    void aClientOpenedLater_getsTheCurrentValue() {
        HostSettings settings = HostSettings.open(home);
        settings.set(SettingKeys.WALK_FREE_TO_PLAY, FreeToPlayRouting.RESTRICT_WHEN_FREE_TO_PLAY);
        new FreeToPlayRoutingSwitch(settings).bind(context());

        GameAPIImpl later = connect("BotWithUs_2");
        bus.publish(new HostEvent.ClientOpened(new ClientRef("BotWithUs_2"), Instant.now()));

        assertTrue(bus.flush(DELIVERY));
        verify(later).setRestrictFreeToPlay(true);
    }

    @Test
    void aClientNotYetWired_isSkipped() {
        Connection bare = mock(Connection.class);
        connections.add(bare);

        assertDoesNotThrow(() -> new FreeToPlayRoutingSwitch(HostSettings.open(home)).bind(context()));
    }

    @Test
    void anUnboundSwitch_touchesNoClient() {
        GameAPIImpl api = connect("BotWithUs_1");

        new FreeToPlayRoutingSwitch(HostSettings.open(home));

        verify(api, never()).setRestrictFreeToPlay(anyBoolean());
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
