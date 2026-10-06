package com.botwithus.bot.core.runlog;

import com.botwithus.bot.api.runtime.Phase;

/**
 * The {@code phase} a crash block names (spec §1.3). A superset of the API's
 * {@link Phase}: {@link #LOAD} and {@link #OTHER} exist only in run logs, so
 * adding them costs the public enum nothing.
 */
public enum CrashPhase {
    ON_START("on_start"),
    ON_LOOP("on_loop"),
    ON_STOP("on_stop"),
    ON_CONFIG_UPDATE("on_config_update"),
    LOAD("load"),
    OTHER("other");

    private final String wireName;

    CrashPhase(String wireName) {
        this.wireName = wireName;
    }

    /** The spelling written to the file. */
    public String wireName() {
        return wireName;
    }

    /** The run-log phase for a lifecycle phase the runner reports. */
    public static CrashPhase of(Phase phase) {
        return switch (phase) {
            case ON_START -> ON_START;
            case ON_LOOP -> ON_LOOP;
            case ON_STOP -> ON_STOP;
            case ON_CONFIG_UPDATE -> ON_CONFIG_UPDATE;
        };
    }
}
