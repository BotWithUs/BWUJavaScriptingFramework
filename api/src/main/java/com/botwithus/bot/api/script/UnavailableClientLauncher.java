package com.botwithus.bot.api.script;

import java.util.List;
import java.util.function.Consumer;

/** {@link ClientLauncher#unavailable()}: every call fails as if no service were running. */
final class UnavailableClientLauncher implements ClientLauncher {

    private static final String MESSAGE = "This host does not talk to the BotWithUs launcher service.";

    @Override
    public List<LauncherAccount> accounts() {
        throw unavailable();
    }

    @Override
    public LaunchHandle launch(String accountId, LaunchOptions options) {
        throw unavailable();
    }

    @Override
    public void stop(String clientId, StopMode mode) {
        throw unavailable();
    }

    @Override
    public List<LaunchedClient> clients() {
        throw unavailable();
    }

    @Override
    public AutoCloseable onEvent(Consumer<LauncherEvent> listener) {
        throw unavailable();
    }

    private static LauncherException unavailable() {
        return new LauncherException(LauncherException.SERVICE_UNAVAILABLE, MESSAGE);
    }
}
