package com.botwithus.bot.cli.diag;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.settings.FreeToPlayRouting;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.impl.GameAPIImpl;

/**
 * Applies the {@link SettingKeys#WALK_FREE_TO_PLAY} setting to every client.
 *
 * <p>Pushed the same way as {@link WalkerPathOverlay}: a change walks the clients
 * and tells each one, and a client opened later is told on arrival. Each client
 * reads the value when a walk starts, so a walk already running keeps the routing
 * it started with.</p>
 */
public final class FreeToPlayRoutingSwitch {

    private final HostSettings settings;

    /** Follows {@code settings}; nothing is applied until {@link #bind}. */
    public FreeToPlayRoutingSwitch(HostSettings settings) {
        this.settings = settings;
    }

    /**
     * Puts every client {@code ctx} has now, and every client it opens from here
     * on, under the setting, and re-applies it to all of them whenever it changes.
     */
    public void bind(CliContext ctx) {
        settings.onChange(SettingKeys.WALK_FREE_TO_PLAY,
                routing -> ctx.getConnections().forEach(conn -> apply(conn, routing)));
        ctx.getHostEvents().subscribe(event -> {
            if (ClientOpenings.isClientOpened(event)) {
                ctx.getConnections().forEach(this::attach);
            }
        });
        ctx.getConnections().forEach(this::attach);
    }

    /** Applies the current value to one client. A client not yet wired is skipped. */
    public void attach(Connection conn) {
        apply(conn, settings.get(SettingKeys.WALK_FREE_TO_PLAY));
    }

    private static void apply(Connection conn, FreeToPlayRouting routing) {
        GameAPIImpl api = conn.getGameAPI();
        if (api != null) {
            api.setRestrictFreeToPlay(routing.isRestricting());
        }
    }
}
