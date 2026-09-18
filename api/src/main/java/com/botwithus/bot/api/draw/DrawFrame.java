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
     */
    public DrawBatchResult flush() {
        if (pending.isEmpty()) {
            return DrawBatchResult.EMPTY;
        }
        DrawBatchResult total = DrawBatchResult.EMPTY;
        for (int from = 0; from < pending.size(); from += DrawLimits.MAX_BATCH_ITEMS) {
            int to = Math.min(from + DrawLimits.MAX_BATCH_ITEMS, pending.size());
            total = total.merge(api.drawSetBatch(List.copyOf(pending.subList(from, to))));
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
     * Flush. Never throws for refused commands — a debug overlay must not be able
     * to take a script's tick down — but every refusal is logged at WARN. Call
     * {@link #flush()} instead when the script wants to act on the result.
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
}
