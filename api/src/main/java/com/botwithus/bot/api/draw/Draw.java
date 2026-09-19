package com.botwithus.bot.api.draw;

import com.botwithus.bot.api.domain.DrawAPI;

import java.util.Arrays;
import java.util.List;

/**
 * Debug-drawing facade. Singleton per {@code GameAPI}; obtain via {@code api.draw()}.
 *
 * <pre>{@code
 * Draw draw = api.draw();
 * draw.rect("target-box", x, y, w, h).color(Colors.GREEN).thickness(2).ttl(1200).submit();
 * draw.component("inv-slot", 1473, 5).color(Colors.CYAN).submit();
 * draw.npc(npc).color(Colors.RED).label(name).submit();   // follows the NPC
 * draw.clear("target-box");
 *
 * try (DrawFrame frame = draw.frame()) {      // one round-trip on close
 *     frame.line("a", 0, 0, 100, 100).submit();
 *     frame.text("b", 10, 20, "hello").submit();
 * }
 * }</pre>
 *
 * <p><b>Use {@link #frame()} for anything more than a couple of commands a tick.</b>
 * Each {@link DrawBuilder#submit()} straight off this facade is its own round-trip;
 * twenty primitives a tick over twenty round-trips is not a reasonable thing to do
 * to the pipe. A frame collapses the primitives into one
 * {@code debug_draw_set_batch}. Highlights stay one call each wherever they are
 * submitted — no batch on this wire can carry one — so a frame saves nothing on
 * those; see {@link DrawFrame#flush()}.</p>
 *
 * <p><b>Marking something that moves is a highlight, not a rect.</b>
 * {@link DrawTarget#npc(com.botwithus.bot.api.snapshot.Npc)} and its siblings send
 * the producer's semantic highlights, which re-resolve the thing they mark every
 * tick — so the box follows a walking NPC and sits at its real height. A
 * world-space rect at the same tile looks identical on the first frame and is wrong
 * on the second.</p>
 *
 * <p>Keys are the identity here — there is no handle to leak and nothing to free.
 * They are scoped to this connection, so two scripts on two clients may both use
 * {@code "target-box"}; a long-lived script that reuses a key carelessly can still
 * stomp its own drawings, which is the only collision that exists.</p>
 */
public final class Draw implements DrawTarget {

    private final DrawAPI api;

    public Draw(DrawAPI api) {
        this.api = api;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One round-trip. A command the producer refuses raises the transport's
     * unchecked RPC error, carrying the producer's message — unlike a batched submit,
     * which reports refusals as a count. The two failure shapes differ because the
     * wire's do.</p>
     *
     * <p><b>Which method carries it is decided here, by type.</b> This is the one
     * place the two families of {@link DrawCommand} meet a wire call, and it is an
     * exhaustive switch rather than a flag so a third family could not be added
     * without this seam being told about it.</p>
     */
    @Override
    public String submit(DrawCommand command) {
        return switch (command) {
            case DrawCommand.Primitive primitive -> api.drawSet(primitive);
            case DrawCommand.Highlight highlight -> api.drawHighlight(highlight);
        };
    }

    /**
     * Open a batch. Every command submitted to the returned frame is held until it is
     * flushed or closed, and then sent as <b>one</b> {@code debug_draw_set_batch} —
     * plus one {@code highlight_*} call per highlight, which no batch can carry.
     *
     * <p>Use it in try-with-resources; closing flushes. A frame carrying more than
     * {@link DrawLimits#MAX_BATCH_ITEMS} primitives is split across the fewest whole
     * batches that will hold it rather than truncated or refused — see
     * {@link DrawFrame#flush()}, which also states exactly what is applied when a
     * flush fails part-way.</p>
     */
    public DrawFrame frame() {
        return new DrawFrame(api);
    }

    /** Remove the named commands, answering how many went. Unknown keys are not an error. */
    public int clear(String... keys) {
        return api.drawClear(Arrays.asList(keys));
    }

    /** Remove the named commands, answering how many went. */
    public int clear(List<String> keys) {
        return api.drawClear(keys);
    }

    /**
     * Remove everything this connection drew, answering how many went. Never
     * touches another client's drawings.
     */
    public int clearAll() {
        return api.drawClearAll();
    }

    /**
     * Every command this connection currently holds.
     *
     * <p>Costs one round-trip per 128 retained commands. {@link DrawEntry#remainingTtlMs()}
     * is time <b>left</b>, not the TTL you sent.</p>
     */
    public List<DrawEntry> list() {
        return api.drawList();
    }

    /** Whether the producer is currently drawing at all. */
    public boolean isEnabled() {
        return api.isDrawEnabled();
    }

    /**
     * Switch drawing on or off, answering the state that took effect. This is
     * process-wide on the producer, not per connection: turning it off hides every
     * client's drawings, not only this script's.
     */
    public boolean setEnabled(boolean enabled) {
        return api.setDrawEnabled(enabled);
    }

    /**
     * The producer's overlay counters and renderer health. The place to look when
     * a command was accepted but nothing appeared — see {@link DrawStats}.
     */
    public DrawStats stats() {
        return api.drawStats();
    }
}
