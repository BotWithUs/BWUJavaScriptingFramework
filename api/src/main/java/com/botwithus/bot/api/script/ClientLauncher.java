package com.botwithus.bot.api.script;

import java.util.List;
import java.util.function.Consumer;

/**
 * Launches, stops and watches game clients through the BotWithUs launcher's
 * background service. A {@link ManagementScript} reaches it through
 * {@link ManagementContext#clientLauncher()}.
 *
 * <p>Every call is a round trip to the service. A call that fails throws
 * {@link LauncherException}; its {@link LauncherException#code() code} is what
 * to branch on. Two codes are raised by the host itself, because no service
 * answered: {@link LauncherException#SERVICE_UNAVAILABLE} (the service is not
 * running, is restarting, or did not answer in time) and
 * {@link LauncherException#SERVICE_STOPPED} (the user stopped it from the tray).
 * The host never starts the service; it keeps reconnecting while it runs.</p>
 *
 * <p><b>Scope.</b> A script that does not manage the whole host sees only the
 * accounts and clients its {@linkplain ManagementContext#targets() targets}
 * cover. Unlike the {@link ClientOrchestrator}, which answers an out-of-scope
 * call with a failed result, this interface <em>throws</em>
 * {@link LauncherException#NOT_PERMITTED}: every other failure here is an
 * exception too, so one more kind of refusal is reported the same way. A script
 * paused on a group may still see and stop that group's clients, but may not
 * launch one.</p>
 *
 * <p><b>After the service comes back</b> (a {@link LauncherEvent.ServiceRestored}
 * event), events sent while it was away are gone and none are replayed. Call
 * {@link #clients()} to see where things stand.</p>
 */
public interface ClientLauncher {

    /**
     * The launcher's accounts this script may launch.
     *
     * @return the accounts, in the service's order; never {@code null}
     */
    List<LauncherAccount> accounts();

    /**
     * Asks the service to launch a client on {@code accountId}. Returns once the
     * launch is queued. The host attaches to the client when the service reports
     * it {@linkplain LaunchedClient.State#INJECTED injected}, and
     * {@link LaunchHandle#outcome()} says how that went.
     *
     * @param accountId an id from {@link #accounts()}
     * @param options   launch options
     * @return the queued launch
     * @throws LauncherException {@code rate_limited} (see
     *                           {@link LauncherException#retryAfterMs()}),
     *                           {@code session_limit}, {@code concurrency_cap},
     *                           {@code not_permitted}, or another code
     */
    LaunchHandle launch(String accountId, LaunchOptions options);

    /**
     * {@link #launch(String, LaunchOptions)} with {@link LaunchOptions#defaults()}.
     *
     * @param accountId an id from {@link #accounts()}
     * @return the queued launch
     */
    default LaunchHandle launch(String accountId) {
        return launch(accountId, LaunchOptions.defaults());
    }

    /**
     * Stops a client. Returns once the stop is issued; a
     * {@link LauncherEvent.ClientExited} follows.
     *
     * @param clientId a {@link LaunchedClient#clientId()}
     * @param mode     how to stop it
     */
    void stop(String clientId, StopMode mode);

    /**
     * The clients the service manages that this script may see.
     *
     * @return the clients; never {@code null}
     */
    List<LaunchedClient> clients();

    /**
     * Registers a listener for launcher events. It runs on a thread of its own,
     * never on the thread reading the service, and with this script's logging
     * context. Close the returned handle to stop listening.
     *
     * @param listener receives each event in order
     * @return closes the subscription
     */
    AutoCloseable onEvent(Consumer<LauncherEvent> listener);

    /**
     * A launcher whose every call throws
     * {@link LauncherException#SERVICE_UNAVAILABLE}: what a host that does not
     * talk to the launcher service offers.
     *
     * @return a new unavailable launcher
     */
    static ClientLauncher unavailable() {
        return new UnavailableClientLauncher();
    }
}
