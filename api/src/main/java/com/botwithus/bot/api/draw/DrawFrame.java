package com.botwithus.bot.api.draw;

import com.botwithus.bot.api.domain.DrawAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A tick's worth of drawing, collapsed into one round-trip.
 *
 * <pre>{@code
 * try (DrawFrame frame = draw.frame()) {
 *     frame.rect("box", x, y, w, h).color(Colors.GREEN).submit();
 *     frame.text("label", x, y - 12, npc.name()).submit();
 * }   // one debug_draw_set_batch happens here
 * }</pre>
 *
 * <p>Commands submitted to a frame are held, not sent. {@link #close()} sends them
 * and is what try-with-resources calls; {@link #flush()} is the same send with the
 * result handed back. Both are safe to call repeatedly — a frame with nothing
 * pending sends nothing.</p>
 *
 * <p>Not thread-safe, and not meant to be: a frame is a tick-scoped local.</p>
 */
public final class DrawFrame implements DrawTarget, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DrawFrame.class);

    private final DrawAPI api;
    private final List<DrawCommand> pending = new ArrayList<>();

    DrawFrame(DrawAPI api) {
        this.api = api;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Queues rather than sends. The key is answered immediately because it is
     * the caller's own — nothing about it depends on the producer.</p>
     */
    @Override
    public String submit(DrawCommand command) {
        pending.add(command);
        return command.key();
    }

    /** How many commands are waiting to be sent. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Send everything queued and answer what the producer did with it.
     *
     * <p><b>One {@code debug_draw_set_batch} per {@link DrawLimits#MAX_BATCH_ITEMS}
     * commands</b> — so exactly one for any frame of that size or smaller, which is
     * every realistic frame. Beyond it the frame is split rather than truncated or
     * refused, because the producer rejects an over-size batch outright and would
     * lose the whole frame including the part that would have fit.</p>
     *
     * <p>The returned result is the aggregate. Check {@link DrawBatchResult#dropped()}:
     * the wire reports per-item refusals inside a <i>successful</i> reply, so a
     * frame that drew nothing does not throw. Anything refused is also logged at
     * WARN, so a caller that ignores this return is still told.</p>
     *
     * <p><b>If this throws, the store is in an unknown state — not a clean one.</b></p>
     *
     * <p>The producer applies each item to its store <i>as it walks the array</i>,
     * so a batch that fails at the envelope level may still have drawn part of what
     * it carried, and the error reply says nothing about how far it got. Splitting
     * sharpens that: when a later sub-batch fails, every command in an earlier one
     * is definitely drawn.</p>
     *
     * <p>Two things make it recoverable, and both are deliberate:</p>
     * <ul>
     *   <li>The pending commands are <b>kept</b> rather than cleared, so the keys
     *       are still in hand and calling {@code flush()} again is safe — setting a
     *       key replaces rather than appends, so a redelivered command is a no-op
     *       rather than a duplicate.</li>
     *   <li>{@link Draw#list()} reports what the producer actually retained, and
     *       {@link Draw#clearAll()} removes all of it without needing any keys at
     *       all.</li>
     * </ul>
     *
     * <p>In practice a content-level rejection is not reachable from this API — see
     * {@code DrawCodecTest} for why the encoder cannot emit an item the producer
     * would refuse structurally — so a throw here is a transport or script-lifecycle
     * failure. When it is severe enough to close the pipe, the producer drops
     * everything this connection drew, which leaves the store clean rather than
     * half-full; a revoked script is the case where earlier sub-batches stay drawn
     * until their TTL expires.</p>
     */
    public DrawBatchResult flush() {
        if (pending.isEmpty()) {
            return DrawBatchResult.EMPTY;
        }
        DrawBatchResult total = DrawBatchResult.EMPTY;
        try {
            for (int from = 0; from < pending.size(); from += DrawLimits.MAX_BATCH_ITEMS) {
                int to = Math.min(from + DrawLimits.MAX_BATCH_ITEMS, pending.size());
                total = total.merge(api.drawSetBatch(List.copyOf(pending.subList(from, to))));
            }
        } catch (RuntimeException e) {
            warnPartiallyApplied(total);
            throw e;
        }
        pending.clear();
        warnIfIncomplete(total);
        return total;
    }

    /**
     * Discard everything queued without sending it. Answers how many were dropped
     * on the floor.
     */
    public int abandon() {
        int count = pending.size();
        pending.clear();
        return count;
    }

    /**
     * Flush. Never throws for <i>refused</i> commands — a debug overlay must not be
     * able to take a script's tick down — but every refusal is logged at WARN. Call
     * {@link #flush()} instead when the script wants to act on the result.
     *
     * <p>A transport failure does still propagate, because it is not the overlay
     * failing: swallowing it would hide a dead pipe. Note that a throw from here
     * inside try-with-resources is <b>suppressed</b> when the block's own body
     * already threw, so the WARN {@link #flush()} logs is the reliable record that
     * the store may hold part of this frame.</p>
     */
    @Override
    public void close() {
        flush();
    }

    private static void warnIfIncomplete(DrawBatchResult result) {
        if (result.isComplete()) {
            return;
        }
        log.warn("debug draw frame: producer refused {} of {} commands; first error: {}",
                result.dropped(), result.submitted(), result.firstError());
    }

    /**
     * An envelope failure means unknown store state, so say so rather than let the
     * exception imply nothing was drawn. The count is what the producer confirmed
     * it stored in sub-batches that completed; the failing one is the unknown.
     */
    private void warnPartiallyApplied(DrawBatchResult confirmed) {
        log.warn("debug draw frame failed part-way: the producer confirmed {} of {} commands "
                        + "stored, and whether the rest reached the store is unknown. The frame "
                        + "keeps its commands, so re-flushing is safe (a key replaces); "
                        + "draw.list() reports what is retained and draw.clearAll() removes it.",
                confirmed.applied(), pending.size());
    }
}
