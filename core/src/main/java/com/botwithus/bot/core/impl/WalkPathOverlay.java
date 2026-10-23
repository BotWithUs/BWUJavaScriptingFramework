package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.Draw;
import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawFrame;
import com.botwithus.bot.core.impl.WalkPathLayout.PlannedPath;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwPathResult;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Draws one world walk's planned route over the client, for as long as the walk runs and
 * the host's "Draw WorldWalker path" setting is on.
 *
 * <p><b>Off the executor's path.</b> The walk executor reports progress through
 * {@link #onEvent} and {@link #onClick}; both only record state and queue a redraw on
 * {@code worker}, so the executor never waits on a planner query or a draw round-trip.
 * Redraws coalesce: a burst of events while one is queued costs one redraw, which reads
 * the newest state when it runs.</p>
 *
 * <p><b>The plan.</b> The executor plans natively and never hands its plan back, so the
 * overlay asks {@code planner} for the same route from the player's tile: once on the
 * first redraw, and again after every {@code REPLAN_STARTED}. That route starts where the
 * player stands, so its step 0 is wherever the executor had got to when it was asked for.
 * The executor's {@code STEP_ADVANCED} indices are therefore taken relative to the index
 * it had reached at that moment, which is what lets the setting be switched on mid-walk.
 * It is still a second query rather than the executor's own plan, so the two can
 * disagree; an index past the end of the drawn route simply draws nothing more.</p>
 *
 * <p><b>Drawing must never break walking.</b> Any {@link RuntimeException} out of the
 * planner or the sink - a dead pipe, an agent that predates the draw handlers - and any
 * command the producer refuses switches the overlay off for the rest of this walk, with
 * one WARN. Nothing propagates to the executor. Whatever was already drawn is cleared,
 * retried on {@link #close()} if that clear fails too, and the finite TTL of every command
 * ({@link WalkPathLayout#TTL_MS}) removes it if even that fails.</p>
 *
 * <p><b>Staying drawn.</b> While a walk is drawn and quiet, a redraw is re-armed every
 * {@code keepAliveMs} so the TTL never lapses mid-walk.</p>
 *
 * <p><b>Clearing.</b> Only keys this overlay drew are ever cleared - never
 * {@code clearAll}, which would wipe the drawings of every script on the connection. The
 * path is cleared on {@code ARRIVED}, {@code FAILED}, {@link #close()} (the walk lease
 * ending, cancelled or not) and on a redraw that finds the setting off. A redraw that was
 * already planning when one of those happened re-checks before it draws, so it cannot
 * put a path back on screen after the clear.</p>
 */
final class WalkPathOverlay {

    private static final Logger log = LoggerFactory.getLogger(WalkPathOverlay.class);

    /** Plans a route from {@code start} to the walk's goal; {@code null} when there is none. */
    @FunctionalInterface
    interface Planner {
        WwPathResult plan(WwTile start);
    }

    /** Where overlay commands go: one batch per redraw, plus clears of keys it set. */
    interface Sink {

        /** Send {@code commands} together, answering what the producer did with them. */
        DrawBatchResult draw(List<DrawCommand> commands);

        /** Remove the named commands. */
        void clear(List<String> keys);
    }

    /** The production {@link Sink}: one {@link DrawFrame} per redraw over {@code draw}. */
    record DrawSink(Draw draw) implements Sink {

        DrawSink {
            Objects.requireNonNull(draw, "draw");
        }

        /**
         * Flushes explicitly rather than through try-with-resources: {@code close()} is a
         * second flush, and after a failed one it would re-send the same frame down a pipe
         * that just failed.
         */
        @Override
        public DrawBatchResult draw(List<DrawCommand> commands) {
            DrawFrame frame = draw.frame();
            commands.forEach(frame::submit);
            return frame.flush();
        }

        @Override
        public void clear(List<String> keys) {
            draw.clear(keys);
        }
    }

    /**
     * What one redraw works from, read under the lock in one go.
     *
     * @param path      the stored route, if one has been planned
     * @param step      the executor's current step index
     * @param planStep  the executor's step index when {@code path} was asked for
     * @param needsPlan whether this redraw must plan; it has claimed the request
     */
    private record State(Optional<PlannedPath> path, int step, int planStep,
                         Optional<WwTile> target, boolean needsPlan) {

        /** The current step, counted in the stored route's own indices. */
        int stepInPath() {
            return Math.max(0, step - planStep);
        }
    }

    private final WalkPathLayout layout;
    private final Planner planner;
    private final Sink sink;
    private final Supplier<Optional<WwTile>> position;
    private final BooleanSupplier isEnabled;
    private final ScheduledExecutorService worker;
    private final long keepAliveMs;

    private final Object lock = new Object();
    // Guarded by lock: written by the executor thread, read by the worker.
    private Optional<PlannedPath> path = Optional.empty();
    private Optional<WwTile> target = Optional.empty();
    private int step;
    private int planStep;
    private boolean needsPlan = true;
    private boolean isFinished;

    private final AtomicBoolean isQueued = new AtomicBoolean();
    private volatile boolean isBroken;
    private volatile boolean hasDrawn;
    // Confined to the worker: the keys currently on screen, and the pending keep-alive.
    private final Set<String> drawn = new HashSet<>();
    private Optional<Future<?>> keepAlive = Optional.empty();

    /**
     * @param layout      key scheme and geometry for this walk
     * @param planner     the executor's planner, fed the executor's capability and instance
     *                    inputs
     * @param sink        where commands are sent
     * @param position    the player's tile now, empty when there is no snapshot
     * @param isEnabled   the host setting, read live
     * @param worker      single-threaded; every redraw runs here, and {@link #close()} shuts
     *                    it down
     * @param keepAliveMs how long a drawn, quiet walk waits before redrawing to renew TTLs
     */
    WalkPathOverlay(WalkPathLayout layout, Planner planner, Sink sink,
                    Supplier<Optional<WwTile>> position, BooleanSupplier isEnabled,
                    ScheduledExecutorService worker, long keepAliveMs) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.position = Objects.requireNonNull(position, "position");
        this.isEnabled = Objects.requireNonNull(isEnabled, "isEnabled");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.keepAliveMs = keepAliveMs;
    }

    /** Executor progress. Never throws; called on the executor thread. */
    void onEvent(WwEvent event) {
        synchronized (lock) {
            switch (event.kind()) {
                case STEP_ADVANCED -> step = Math.max(0, event.stepIndex());
                case REPLAN_STARTED -> {
                    needsPlan = true;
                    step = 0;
                    planStep = 0;
                }
                case ARRIVED, FAILED -> isFinished = true;
                default -> {
                    return;
                }
            }
        }
        schedule();
    }

    /** The tile the executor just clicked toward. Never throws; called on the executor thread. */
    void onClick(WwTile tile) {
        synchronized (lock) {
            target = Optional.of(tile);
        }
        schedule();
    }

    /**
     * Redraw from current state. Called when the setting changes either way, so it always
     * queues: a redraw already planning may otherwise be the last one to run.
     */
    void refresh() {
        enqueue();
    }

    /**
     * The walk lease ended: queue a final clear, then let the worker wind down. Always
     * queues, whatever the setting says or whether anything is drawn yet, because a redraw
     * may be mid-plan and about to draw.
     */
    void close() {
        synchronized (lock) {
            isFinished = true;
        }
        enqueue();
        worker.shutdown();
    }

    /** Whether a failure has switched drawing off for this walk. */
    boolean isBroken() {
        return isBroken;
    }

    /** A redraw for walk progress: skipped when there is nothing to draw or clear. */
    private void schedule() {
        if (isBroken || (!hasDrawn && !isEnabled.getAsBoolean())) {
            return;
        }
        enqueue();
    }

    private void enqueue() {
        if (!isQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::render);
        } catch (RejectedExecutionException e) {
            isQueued.set(false);
        }
    }

    private void render() {
        isQueued.set(false);
        try {
            renderState(takeState());
        } catch (RuntimeException e) {
            disable(e.toString());
        }
    }

    private State takeState() {
        synchronized (lock) {
            State state = new State(path, step, planStep, target, needsPlan);
            needsPlan = false;
            return state;
        }
    }

    private void renderState(State state) {
        if (!shouldDraw()) {
            releasePlanRequest(state);
            clearDrawn();
            return;
        }
        Optional<WwTile> here = position.get();
        if (here.isEmpty()) {
            releasePlanRequest(state);
            return;
        }
        Optional<PlannedPath> route = state.needsPlan()
                ? replan(here.get(), state.step())
                : state.path();
        int stepInPath = state.needsPlan() ? 0 : state.stepInPath();
        List<DrawCommand> commands =
                layout.commands(route, stepInPath, here.get().plane(), state.target());
        if (!shouldDraw()) {
            // The walk ended or the setting went off while this redraw was planning.
            clearDrawn();
            return;
        }
        send(commands);
        armKeepAlive();
    }

    private boolean shouldDraw() {
        synchronized (lock) {
            return !isBroken && !isFinished && isEnabled.getAsBoolean();
        }
    }

    /** Hands an unserved plan request back, so the next redraw that can plan does. */
    private void releasePlanRequest(State state) {
        if (!state.needsPlan()) {
            return;
        }
        synchronized (lock) {
            needsPlan = true;
        }
    }

    /**
     * Plans from {@code start} and stores the route against {@code stepAtRequest}, the
     * executor's step when this plan was asked for. A {@code REPLAN_STARTED} arriving
     * meanwhile wins: it re-raises the request, and this route is stored anyway as the
     * best one in hand until the next redraw replaces it.
     */
    private Optional<PlannedPath> replan(WwTile start, int stepAtRequest) {
        WwPathResult result = planner.plan(start);
        Optional<PlannedPath> planned = result == null
                ? Optional.empty()
                : Optional.of(new PlannedPath(start, result.steps()));
        synchronized (lock) {
            path = planned;
            planStep = stepAtRequest;
        }
        return planned;
    }

    private void send(List<DrawCommand> commands) {
        Set<String> keys = new HashSet<>();
        commands.forEach(c -> keys.add(c.key()));
        if (!commands.isEmpty()) {
            drawn.addAll(keys);
            hasDrawn = true;
            DrawBatchResult result = sink.draw(commands);
            if (!result.isComplete()) {
                disable("the producer refused " + result.dropped() + " of "
                        + result.submitted() + " commands: " + result.firstError());
                return;
            }
        }
        List<String> stale = drawn.stream().filter(k -> !keys.contains(k)).toList();
        if (!stale.isEmpty()) {
            sink.clear(stale);
            stale.forEach(drawn::remove);
        }
        hasDrawn = !drawn.isEmpty();
    }

    /**
     * Removes everything drawn. Keys are forgotten only once the clear has gone through,
     * so a clear that throws is retried by the next redraw - {@link #close()} always
     * queues one.
     */
    private void clearDrawn() {
        cancelKeepAlive();
        if (drawn.isEmpty()) {
            return;
        }
        List<String> keys = List.copyOf(drawn);
        sink.clear(keys);
        keys.forEach(drawn::remove);
        hasDrawn = !drawn.isEmpty();
    }

    private void armKeepAlive() {
        cancelKeepAlive();
        if (!hasDrawn) {
            return;
        }
        try {
            keepAlive = Optional.of(
                    worker.schedule(this::refresh, keepAliveMs, TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException e) {
            keepAlive = Optional.empty();
        }
    }

    private void cancelKeepAlive() {
        keepAlive.ifPresent(f -> f.cancel(false));
        keepAlive = Optional.empty();
    }

    private void disable(String reason) {
        if (!isBroken) {
            isBroken = true;
            log.warn("WorldWalker path overlay off for the rest of this walk ({}): {}",
                    layout.keyPrefix(), reason);
        }
        try {
            clearDrawn();
        } catch (RuntimeException e) {
            log.debug("WorldWalker path overlay: clearing after failure also failed: {}",
                    e.toString());
        }
    }
}
