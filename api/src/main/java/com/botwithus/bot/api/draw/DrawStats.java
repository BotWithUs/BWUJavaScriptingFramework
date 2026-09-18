package com.botwithus.bot.api.draw;

/**
 * The producer's own account of its overlay: how full the store is, what it has
 * refused, and whether the renderer is alive.
 *
 * <p>The failure this exists to catch is an overlay that has silently died while
 * looking perfectly healthy. Three fields are the ones worth asserting on:</p>
 *
 * <ul>
 *   <li>{@link #presentFailures()} — uploads that failed. The real health check,
 *       and falsifiable: break the present and it climbs.</li>
 *   <li>{@link #hasPresented()} — the renderer has drawn at least one frame. False
 *       means a dead overlay, which otherwise looks exactly like an idle one.</li>
 *   <li>{@link #isResolverLive()} — the game thread has returned resolved geometry
 *       at least once. "Rect unresolved" is also the initial state, so asserting
 *       only that would pass just as happily with the per-tick refresh never
 *       having run.</li>
 * </ul>
 *
 * <p>Do not build a check on frames matching presents. They answer different
 * questions and differ in normal operation; the producer reports their difference
 * as a diagnostic rather than a verdict, and this record deliberately does not
 * carry either counter.</p>
 *
 * @param count               retained commands, all connections
 * @param capacity            {@link DrawLimits#MAX_COMMANDS}
 * @param textSlotsUsed       retained text commands
 * @param textSlotCapacity    {@link DrawLimits#MAX_TEXT_COMMANDS}
 * @param polySlotsUsed       retained polyline commands
 * @param polySlotCapacity    {@link DrawLimits#MAX_POLY_COMMANDS}
 * @param liveTargets         component highlights awaiting per-tick resolution
 * @param dropped             commands refused for want of a slot, all connections, since
 *                            the producer started. A malformed command, an over-long key
 *                            and an out-of-range coordinate are rejected before the store
 *                            is touched and do <b>not</b> land here — this counter is not
 *                            a way to detect them. Not to be confused with
 *                            {@link DrawBatchResult#dropped()}, which is per-call and
 *                            <i>does</i> count those: same name, different scope, and
 *                            different membership.
 * @param resolveFailures     component rects the game thread could not resolve
 * @param resolveOverflow     ticks on which more than
 *                            {@link DrawLimits#RESOLVED_COMPONENTS_PER_TICK} highlights
 *                            were live. Non-zero means some highlight geometry is lagging
 *                            the game by a tick or more; collection rotates, so nothing is
 *                            starved permanently.
 * @param isEnabled           drawing is switched on
 * @param hasPresented        the renderer has delivered at least one frame
 * @param presentFailures     failed uploads
 * @param backendPresents     uploads that reached the screen, cumulative. Useful as a
 *                            progress counter — a value higher than one sampled earlier
 *                            means a frame was delivered in between. Do <b>not</b>
 *                            compare it against the producer's frame counter: a frame
 *                            whose dirty region is empty is processed without uploading
 *                            anything, so they differ in normal operation and neither
 *                            "equal" nor "unequal" means healthy.
 * @param isResolverLive      the per-tick component resolve has run at least once
 * @param backend             which renderer is live, as the producer names it
 * @param surfaceWidth        overlay surface width in pixels
 * @param surfaceHeight       overlay surface height in pixels
 */
public record DrawStats(int count, int capacity, int textSlotsUsed, int textSlotCapacity,
                        int polySlotsUsed, int polySlotCapacity, int liveTargets,
                        long dropped, long resolveFailures, long resolveOverflow,
                        boolean isEnabled, boolean hasPresented, long presentFailures,
                        boolean isResolverLive, long backendPresents, String backend,
                        int surfaceWidth, int surfaceHeight) {

    public DrawStats {
        backend = backend == null ? "" : backend;
    }

    /** True when the store has no room for another command. */
    public boolean isFull() {
        return count >= capacity;
    }

    /**
     * True when more component highlights are live than the producer resolves per
     * tick, so some of their geometry is a tick or more behind the game.
     */
    public boolean isResolveLagging() {
        return liveTargets > DrawLimits.RESOLVED_COMPONENTS_PER_TICK;
    }
}
