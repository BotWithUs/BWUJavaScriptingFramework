package com.botwithus.bot.cli.diag;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.impl.GameAPIImpl;

/**
 * Applies the {@link SettingKeys#DRAW_WALKER_PATH} switch to every client.
 *
 * <p>Unlike the collection switches this one is pushed rather than polled: turning
 * it off has to clear a path already on screen, so a change walks the clients and
 * tells each one. A walk in progress picks the change up at once, either way.</p>
 */
public final class WalkerPathOverlay {

    private final HostSettings settings;

    /** Follows {@code settings}; nothing is applied until {@link #bind}. */
    public WalkerPathOverlay(HostSettings settings) {
        this.settings = settings;
    }

    /**
     * Puts every client {@code ctx} has now, and every client it opens from here
     * on, under the switch, and re-applies it to all of them whenever it changes.
     */
    public void bind(CliContext ctx) {
        settings.onChange(SettingKeys.DRAW_WALKER_PATH,
                isOn -> ctx.getConnections().forEach(conn -> apply(conn, isOn)));
        ctx.getHostEvents().subscribe(event -> {
            if (ClientOpenings.isClientOpened(event)) {
                ctx.getConnections().forEach(this::attach);
            }
        });
        ctx.getConnections().forEach(this::attach);
    }

    /** Applies the current value to one client. A client not yet wired is skipped. */
    public void attach(Connection conn) {
        apply(conn, settings.get(SettingKeys.DRAW_WALKER_PATH));
    }

    private static void apply(Connection conn, boolean isOn) {
        GameAPIImpl api = conn.getGameAPI();
        if (api != null) {
            api.setDrawWalkerPath(isOn);
        }
    }
}
