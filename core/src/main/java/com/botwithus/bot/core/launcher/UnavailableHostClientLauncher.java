package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.ClientLauncher;
import com.botwithus.bot.api.script.LaunchHandle;
import com.botwithus.bot.api.script.LaunchOptions;
import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.StopMode;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A {@link HostClientLauncher} for a host with no service connection (a test,
 * or before the composition root has registered): every call throws
 * {@code service_unavailable}, as {@link ClientLauncher#unavailable()} does.
 */
public final class UnavailableHostClientLauncher implements HostClientLauncher {

    private final ClientLauncher unavailable = ClientLauncher.unavailable();

    /** A launcher that is never connected. */
    public UnavailableHostClientLauncher() {
        // Nothing to set up: every call fails the same way.
    }

    @Override
    public List<LauncherAccount> accounts() {
        return unavailable.accounts();
    }

    @Override
    public LaunchHandle launch(String accountId, LaunchOptions options) {
        return unavailable.launch(accountId, options);
    }

    @Override
    public void stop(String clientId, StopMode mode) {
        unavailable.stop(clientId, mode);
    }

    @Override
    public List<LaunchedClient> clients() {
        return unavailable.clients();
    }

    @Override
    public AutoCloseable onEvent(Consumer<LauncherEvent> listener) {
        return unavailable.onEvent(listener);
    }

    @Override
    public Optional<String> accountOf(String clientId) {
        return Optional.empty();
    }
}
