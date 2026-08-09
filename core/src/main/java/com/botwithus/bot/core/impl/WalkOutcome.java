package com.botwithus.bot.core.impl;

/**
 * What became of one owner's last walk request, kept after the
 * {@link WalkLease} has been handed back.
 *
 * <p>Carries the goal alongside the state so {@code getWalkStatus} still
 * reports the tile that was walked to once the walk has ended — the same thing
 * the single per-connection target fields used to report, but scoped to the
 * caller instead of to whoever walked most recently.</p>
 *
 * <p>The sequence number is the issue order of the request this records. Two
 * records for the same owner can be written out of order — a worker that
 * outlived its cancel-join window reports after the walk that replaced it — so
 * the higher sequence wins rather than the later write, and where the sequences
 * are equal the first write wins. See {@code GameAPIImpl.recordOutcome}.</p>
 *
 * @param state   the terminal state of that request
 * @param targetX goal world tile X of that request
 * @param targetY goal world tile Y of that request
 * @param seq     issue order of the request, from the connection's walk counter
 */
record WalkOutcome(WalkState state, int targetX, int targetY, long seq) {

    /**
     * The answer for an owner that has never asked for a walk on this
     * connection. Sequence {@code 0} is below every issued request, so this can
     * never displace a real record.
     */
    static final WalkOutcome NONE = new WalkOutcome(WalkState.IDLE, 0, 0, 0L);
}
