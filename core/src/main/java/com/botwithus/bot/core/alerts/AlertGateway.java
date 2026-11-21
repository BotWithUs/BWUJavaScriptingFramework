package com.botwithus.bot.core.alerts;

import java.util.List;
import java.util.Optional;

/**
 * Where the host hands alerts over: the launcher service, which owns every
 * request that leaves the machine. The host keeps the policy (what to send, when,
 * and to which services); the gateway sends it and keeps each service's set-up.
 *
 * <p>Every call may block on the launcher's pipe, so none is made on the render
 * thread. None throws for a missing or older launcher: each says so in its reply.</p>
 */
public interface AlertGateway {

    /** Said wherever alerts cannot be set up or sent because the launcher predates them. */
    String NEEDS_NEWER_LAUNCHER = "Update the launcher to send alerts";
    /** Said wherever alerts cannot be set up or sent because no launcher is connected. */
    String LAUNCHER_NOT_CONNECTED = "The BotWithUs launcher is not running, so alerts cannot be sent";

    /** What a listener hears. Called on the launcher's reader thread: hand off, never block. */
    interface Listener {

        /** A launcher that sends alerts has (re)connected. */
        void onAvailable();

        /** That launcher went away. */
        void onUnavailable();

        /** A send the launcher made has ended. */
        void onResult(AlertResult result);
    }

    /** Whether a launcher that sends alerts is connected now. */
    boolean isAvailable();

    /** Whether a launcher is connected now, whether or not it sends alerts. */
    boolean isConnected();

    /** Hands {@code delivery} to the launcher to queue. Never sends anything itself. */
    DeliverReply deliver(AlertDelivery delivery);

    /** Every service's set-up, in the launcher's order; empty when it could not be read. */
    Optional<List<IntegrationState>> integrations();

    /** Changes one service's set-up. */
    IntegrationReply change(IntegrationChange change);

    /** Asks for a Send test to {@code service}, whether or not the host has it switched on. */
    TestReply sendTest(AlertService service);

    /** Every service's sending state; empty when it could not be read. */
    Optional<List<ServiceState>> status();

    /**
     * Adds a listener.
     *
     * @return removes it
     */
    Runnable addListener(Listener listener);
}
