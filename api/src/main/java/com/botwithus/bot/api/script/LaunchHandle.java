package com.botwithus.bot.api.script;

import java.util.concurrent.CompletionStage;

/** A launch the service has queued: {@link ClientLauncher#launch(String, LaunchOptions)}. */
public interface LaunchHandle {

    /** @return the service's id for the client; stable across auto-restarts */
    String clientId();

    /**
     * Completes once, when the launch has an outcome: the host attached to the
     * client, the launch failed, or the client was injected but the host could
     * not attach to it. It never completes exceptionally.
     *
     * @return the outcome
     */
    CompletionStage<LaunchOutcome> outcome();
}
