package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.WalkStatus;

/**
 * The state of one owner's walk, as reported by {@code GameAPIImpl.getWalkStatus}.
 *
 * <p>Internal to {@code core}. The published {@link WalkStatus} carries
 * {@link #wireName()} rather than this enum, so adding a state here is additive
 * for scripts — an existing script that compares the string against the names it
 * knows keeps compiling and keeps working.</p>
 */
enum WalkState {

    /** No walk has been requested by this owner, or the connection was torn down. */
    IDLE("idle", false),
    /** This owner holds the movement lease and its executor is running. */
    WALKING("walking", false),
    /** The executor reached the goal. */
    ARRIVED("arrived", true),
    /** The walk was cancelled — by its owner, by the host, or by the script stopping. */
    CANCELLED("cancelled", true),
    /** The executor gave up: no path, stuck too long, or it threw. */
    FAILED("failed", true),
    /**
     * The request was refused because another script was already walking this
     * character. Nothing was started and the incumbent walk was left alone.
     */
    REFUSED_BUSY("refused_busy", true);

    private final String wireName;
    private final boolean done;

    WalkState(String wireName, boolean done) {
        this.wireName = wireName;
        this.done = done;
    }

    /** The name published on {@link WalkStatus#state()}. */
    String wireName() {
        return wireName;
    }

    /** Whether this is a terminal state — the request reached a conclusion. */
    boolean isDone() {
        return done;
    }
}
