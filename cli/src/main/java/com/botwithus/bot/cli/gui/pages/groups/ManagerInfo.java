package com.botwithus.bot.cli.gui.pages.groups;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A group's manager as its slot shows it.
 *
 * @param script      the management script's name
 * @param version     its version, or empty when it is not loaded or gives none
 * @param state       whether it manages the group now
 * @param uptime      how long it has run, while it runs
 * @param lastAction  its latest orchestrator call, "14:05 stopScript · Woodcutting on Duskwater"
 * @param hasSettings whether it declares settings the slot's Settings button can open
 */
public record ManagerInfo(String script, String version, State state, Optional<Duration> uptime,
                          Optional<String> lastAction, boolean hasSettings) {

    /** Whether the manager manages the group now. */
    public enum State {
        /** Running, and free to start and stop scripts on the group. */
        MANAGING("Managing"),
        /** Held back by Stop all: it may stop scripts on the group but starts none. */
        PAUSED("Paused"),
        /** Assigned, but not running. */
        STOPPED("Stopped"),
        /** Assigned, but no JAR in the management folder provides it now. */
        NOT_LOADED("Not loaded");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ManagerInfo {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(uptime, "uptime");
        Objects.requireNonNull(lastAction, "lastAction");
    }

    /** The line under the name, for a group of {@code members} clients. */
    public String line(int members) {
        return switch (state) {
            case MANAGING -> "Running " + uptime.map(GroupText::clock).orElse("0:00") + " · sees only these "
                    + GroupText.count(members, "client") + lastAction.map(a -> " · " + a).orElse("");
            case PAUSED -> "Paused by Stop all, so it does not start what was stopped. Start it to let it"
                    + " manage the group again.";
            case STOPPED -> "Assigned but not running.";
            case NOT_LOADED -> "Assigned, but not in the management folder now, so it cannot run.";
        };
    }

    /** Whether the slot's Start button can do anything: it is not managing, and it is loaded. */
    public boolean canStart() {
        return state == State.PAUSED || state == State.STOPPED;
    }
}
