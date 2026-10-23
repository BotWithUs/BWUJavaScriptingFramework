package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.core.rpc.ReconnectController;

import java.util.Objects;
import java.util.Optional;

/**
 * Feeds the host's toasts from its event bus. The only route by which toasts
 * arrive: the per-connection event buses are not subscribed, because every
 * event a toast is raised for reaches the host bus too, once, whether or not a
 * client is connected.
 */
public final class HostToasts implements ToastFeed.Clients {

    private final CliContext ctx;

    private HostToasts(CliContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Subscribes a {@link ToastFeed} into {@code sink} to {@code ctx}'s host
     * event bus, reading switches and duration from its settings, which must be
     * set first.
     *
     * @return removes the subscription
     */
    public static Runnable attach(CliContext ctx, ToastSink sink) {
        Objects.requireNonNull(ctx.getSettings(), "settings must be set before toasts are attached");
        return ToastFeed.subscribe(ctx.getHostEvents(), sink, ctx.getSettings(), new HostToasts(ctx));
    }

    @Override
    public Optional<String> nameOf(ClientKey key) {
        return ctx.getClientRegistry().get(key).flatMap(ClientRecord::name);
    }

    @Override
    public boolean isGone(ClientRef client) {
        return ctx.getConnections().stream()
                .filter(conn -> conn.getName().equals(client.pipe()))
                .map(Connection::getReconnectController)
                .filter(Objects::nonNull)
                .anyMatch(ReconnectController::isClientGone);
    }
}
