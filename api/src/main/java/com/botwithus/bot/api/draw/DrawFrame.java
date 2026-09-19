package com.botwithus.bot.api.draw;

import com.botwithus.bot.api.domain.DrawAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A tick's worth of drawing, collapsed into as few round-trips as the wire allows.
 *
 * <pre>{@code
 * try (DrawFrame frame = draw.frame()) {
 *     frame.rect("box", x, y, w, h).color(Colors.GREEN).submit();
 *     frame.text("label", x, y - 12, npc.name()).submit();
 *     frame.npc(npc).color(Colors.RED).submit();
 * }   // one debug_draw_set_batch for the primitives, one highlight_entity, here
 * }</pre>
 *
 * <p>Commands submitted to a frame are held, not sent. {@link #close()} sends them
 * and is what try-with-resources calls; {@link #flush()} is the same send with the
 * result handed back. Both are safe to call repeatedly — a frame with nothing
 * pending sends nothing.</p>
 *
 * <p><b>A frame is one round-trip for primitives and one more per highlight</b>, and
 * that is the wire's shape rather than a shortcoming here.
 * {@code debug_draw_set_batch} applies {@code debug_draw_set} items, whose
 * {@code kind} field cannot name a semantic highlight, so there is no batch a
 * {@link DrawCommand.Highlight} could ride. Both families are accepted here anyway,
 * because the alternative — refusing {@code frame.npc(...)} at compile time — would
 * break a call site to tell the caller something they cannot act on, and would
 * scatter a script's drawing across two mechanisms for a reason that is not the
 * script's business. {@link #pendingHighlightCount()} is how many extra calls a
 * flush will make, if it matters.</p>
 *
 * <p>The cost is bounded by what highlights are <i>for</i>: a script marks a handful
 * of tracked things a tick, while the batch exists for the dozens of primitives it
 * draws around them. Twenty rects and three highlights is four round-trips, not
 * twenty-three.</p>
 *
 * <p>Not thread-safe, and not meant to be: a frame is a tick-scoped local.</p>
 */
public final class DrawFrame implements DrawTarget, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DrawFrame.class);

    private final DrawAPI api;

    /**
     * The two families, partitioned on the way in rather than sorted on the way out.
     *
     * <p>Interleaved submission order is not preserved between them, and nothing is
     * owed it: paint order is {@link DrawStyle#z()}, and the producer documents the
     * order of two commands sharing a {@code z} as stable but deliberately
     * unspecified. So there is no ordering contract for splitting them to break.</p>
     */
    private final List<DrawCommand.Primitive> pendingPrimitives = new ArrayList<>();
    private final List<DrawCommand.Highlight> pendingHighlights = new ArrayList<>();

    DrawFrame(DrawAPI api) {
        this.api = api;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Queues rather than sends, into whichever of the two pending lists the
     * command's family belongs to. The key is answered immediately because it is the
     * caller's own — nothing about it depends on the producer.</p>
     */
    @Override
    public String submit(DrawCommand command) {
        switch (command) {
            case DrawCommand.Primitive primitive -> pendingPrimitives.add(primitive);
            case DrawCommand.Highlight highlight -> pendingHighlights.add(highlight);
        }
        return command.key();
    }

    /** How many commands are waiting to be sent, of both families. */
    public int pendingCount() {
        return pendingPrimitives.size() + pendingHighlights.size();
    }

    /**
     * How many of the pending commands are highlights — which is how many round-trips
     * beyond the batch a flush will cost, since none of them can be batched.
     */
    public int pendingHighlightCount() {
        return pendingHighlights.size();
    }

    /**
     * Send everything queued and answer what the producer did with it.
     *
     * <p><b>The primitives go first</b>, as one {@code debug_draw_set_batch} per
     * {@link DrawLimits#MAX_BATCH_ITEMS} of them — so exactly one for any frame of
     * that size or smaller, which is every realistic frame. Beyond it the frame is
     * split rather than truncated or refused, because the producer rejects an
     * over-size batch outright and would lose the whole frame including the part that
     * would have fit. <b>Then each highlight, as its own {@code highlight_*}
     * call</b>, because no batch can carry one.</p>
     *
     * <p>The returned result is the aggregate over all of it. Check
     * {@link DrawBatchResult#dropped()}: the wire reports per-item refusals inside a
     * <i>successful</i> reply, so a frame that drew nothing does not throw. A refused
     * highlight is folded into the same count rather than thrown, so one contract
     * covers the whole frame. Anything refused is also logged at WARN, so a caller
     * that ignores this return is still told.</p>
     *
     * <p><b>If this throws, the store is in a partly-known state — not a clean
     * one.</b></p>
     *
     * <p>What is applied when it does, in the order the flush attempts them:</p>
     * <ol>
     *   <li><b>Sub-batches that completed</b> are entirely drawn. The producer applies
     *       each item to its store <i>as it walks the array</i>, so a batch that fails
     *       at the envelope level may still have drawn part of what it carried, and
     *       the error reply says nothing about how far it got. Splitting sharpens
     *       that: when a later sub-batch fails, every command in an earlier one is
     *       definitely drawn.</li>
     *   <li><b>The failing sub-batch is the unknown.</b> Somewhere between none and
     *       all of it reached the store.</li>
     *   <li><b>Highlights are not half-applied.</b> Each is a single store operation
     *       behind a single call, so every highlight before a failure is drawn and the
     *       failing one is not. That is strictly more determinate than the batch half
     *       — the one thing sending them separately buys.</li>
     *   <li><b>Nothing after the failure was sent at all.</b></li>
     * </ol>
     *
     * <p>Two things make it recoverable, and both are deliberate:</p>
     * <ul>
     *   <li>The pending commands are <b>kept</b> rather than cleared, so the keys are
     *       still in hand and calling {@code flush()} again is safe — setting a key
     *       replaces rather than appends, so a redelivered command is a no-op rather
     *       than a duplicate.</li>
     *   <li>{@link Draw#list()} reports what the producer actually retained, and
     *       {@link Draw#clearAll()} removes all of it without needing any keys at
     *       all.</li>
     * </ul>
     *
     * <p>In practice a content-level rejection is not reachable from this API — see
     * {@code DrawCodecTest} for why the encoder cannot emit an item the producer would
     * refuse structurally — so a throw here is a transport or script-lifecycle
     * failure. When it is severe enough to close the pipe, the producer drops
     * everything this connection drew, which leaves the store clean rather than
     * half-full; a revoked script is the case where earlier calls stay drawn until
     * their TTL expires.</p>
     */
    public DrawBatchResult flush() {
        if (pendingCount() == 0) {
            return DrawBatchResult.EMPTY;
        }
        DrawBatchResult total = DrawBatchResult.EMPTY;
        try {
            for (int from = 0; from < pendingPrimitives.size();
                    from += DrawLimits.MAX_BATCH_ITEMS) {
                int to = Math.min(from + DrawLimits.MAX_BATCH_ITEMS, pendingPrimitives.size());
                total = total.merge(api.drawSetBatch(
                        List.copyOf(pendingPrimitives.subList(from, to))));
            }
            // One at a time, deliberately. drawHighlights already makes one call per
            // highlight, so this costs nothing — but when it throws on transport it
            // loses the count it had accumulated, and that count is what
            // warnPartiallyApplied reports. Merging per highlight keeps `total` holding
            // every one that actually landed, so the warning's "confirmed N of M" is
            // true for this half too rather than only for the batched one.
            for (DrawCommand.Highlight highlight : pendingHighlights) {
                total = total.merge(api.drawHighlights(List.of(highlight)));
            }
        } catch (RuntimeException e) {
            warnPartiallyApplied(total);
            throw e;
        }
        pendingPrimitives.clear();
        pendingHighlights.clear();
        warnIfIncomplete(total);
        return total;
    }

    /**
     * Discard everything queued without sending it. Answers how many were dropped on
     * the floor, of both families.
     */
    public int abandon() {
        int count = pendingCount();
        pendingPrimitives.clear();
        pendingHighlights.clear();
        return count;
    }

    /**
     * Flush. Never throws for <i>refused</i> commands — a debug overlay must not be
     * able to take a script's tick down, and that holds for a refused highlight as
     * much as for a refused rect — but every refusal is logged at WARN. Call
     * {@link #flush()} instead when the script wants to act on the result.
     *
     * <p>A transport failure does still propagate, because it is not the overlay
     * failing: swallowing it would hide a dead pipe. Note that a throw from here
     * inside try-with-resources is <b>suppressed</b> when the block's own body already
     * threw, so the WARN {@link #flush()} logs is the reliable record that the store
     * may hold part of this frame.</p>
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
     * An envelope failure means partly-known store state, so say so rather than let the
     * exception imply nothing was drawn. The count is what the producer confirmed it
     * stored in calls that completed; the failing one is the unknown.
     *
     * <p>That sentence is true for both halves only because {@link #flush()} merges each
     * highlight's result as it goes. Sending them as one {@code drawHighlights} call would
     * discard the count it had accumulated when it threw, and this warning would then
     * under-report the highlights by however many had already landed.</p>
     */
    private void warnPartiallyApplied(DrawBatchResult confirmed) {
        log.warn("debug draw frame failed part-way: the producer confirmed {} of {} commands "
                        + "stored, and whether the rest reached the store is unknown. The frame "
                        + "keeps its commands, so re-flushing is safe (a key replaces); "
                        + "draw.list() reports what is retained and draw.clearAll() removes it.",
                confirmed.applied(), pendingCount());
    }
}
