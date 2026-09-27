package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.Objects;

/**
 * Names one script runner: a script on one client. What a runner row's buttons
 * act on; resolved again when clicked, so a runner that has gone is a no-op.
 *
 * @param client the pipe name of the client the script runs on
 * @param script the script's registered name
 */
public record RunnerRef(String client, String script) {

    public RunnerRef {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(script, "script");
    }
}
