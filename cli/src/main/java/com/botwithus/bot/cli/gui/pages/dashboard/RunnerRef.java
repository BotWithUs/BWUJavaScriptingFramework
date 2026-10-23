package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.cli.events.ClientKey;

import java.util.Objects;

/**
 * Names one script runner: a script on one client. What a runner row's buttons
 * act on; resolved again when clicked, so a runner that has gone is a no-op.
 *
 * @param client the key of the client the script runs on
 * @param script the script's registered name
 */
public record RunnerRef(ClientKey client, String script) {

    public RunnerRef {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(script, "script");
    }
}
