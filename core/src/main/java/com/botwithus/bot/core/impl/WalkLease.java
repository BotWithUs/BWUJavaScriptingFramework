package com.botwithus.bot.core.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The right to drive one connection's character on foot, held by exactly one
 * owner at a time.
 *
 * <p>There is one avatar, one tile position, and one server-side action queue
 * that drains a single action per server tick. Two executors walking the same
 * character interleave their per-tile clicks, so neither observes progress
 * toward its own goal, both trip their stuck deadline, and both re-plan from a
 * position neither predicted. That is why a walk request from a second script
 * is refused rather than queued: the failure mode is a livelock that presents
 * as the character jittering in place, which is far harder to diagnose than an
 * error.</p>
 *
 * <p>Leases are compared by reference — {@code compareAndSet} on the holder is
 * what decides who owns the character — so a worker that outlived its
 * cancel-join window can tell it is no longer the holder and stay off its
 * successor's state.</p>
 *
 * <p>{@code worker} is a mutable cell rather than a plain component because the
 * executor thread can only be built <em>after</em> the lease exists: the
 * thread's body needs the lease to release it on the way out. A cancel arriving
 * in that gap finds no thread to join, so the two sides interlock on
 * {@code cancel} instead — the starter writes {@code worker} then reads
 * {@code cancel}, the canceller writes {@code cancel} then reads
 * {@code worker}, and one of them always sees the other. See
 * {@code GameAPIImpl.startWalkWorker}.</p>
 *
 * @param owner   the script that holds the lease, or the host sentinel
 * @param seq     connection-wide issue order, used to keep a late worker from
 *                stamping a stale outcome over a newer walk's
 * @param cancel  polled by the executor's callback bridge; set to stop the walk
 * @param worker  the executor thread, once built
 * @param targetX goal world tile X, reported back through {@code getWalkStatus}
 * @param targetY goal world tile Y, reported back through {@code getWalkStatus}
 */
record WalkLease(String owner, long seq, AtomicBoolean cancel, AtomicReference<Thread> worker,
                 int targetX, int targetY) {

    /** A fresh, uncancelled lease with no worker attached yet. */
    static WalkLease forOwner(String owner, long seq, int targetX, int targetY) {
        return new WalkLease(owner, seq, new AtomicBoolean(false), new AtomicReference<>(),
                targetX, targetY);
    }
}
