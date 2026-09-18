package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.draw.Draw;
import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawEntry;
import com.botwithus.bot.api.draw.DrawFrame;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.draw.DrawStats;

import java.util.List;

/**
 * Debug drawing: retained overlay commands the producer renders over the client.
 *
 * <p>Scripts use {@link #draw()} and the fluent surface behind it. The methods on
 * this mixin are the wire primitives that facade is built from, exposed because
 * every other facade in this API is built the same way — not because a script is
 * expected to call them.</p>
 *
 * <p>Three properties shape all of it:</p>
 * <ul>
 *   <li><b>Keyed, not handled.</b> Setting the same key again replaces rather than
 *       appends, so redrawing every tick is idempotent, and there is nothing to
 *       free.</li>
 *   <li><b>Connection-scoped.</b> Keys cannot collide between clients, a clear
 *       only ever removes your own, and the producer drops everything a connection
 *       drew when its pipe closes.</li>
 *   <li><b>TTL is mandatory.</b> This host always puts one on the wire, defaulting
 *       to {@link DrawLimits#DEFAULT_TTL_MS}.</li>
 * </ul>
 *
 * <p>Producer-side coupling: every method here maps to one handler in
 * {@code NXTLibrary/src/rpc/Handlers.cpp}. The group is additive over the RPC pipe
 * and does <b>not</b> move {@code Layout.PROTOCOL_VERSION}, which gates the shared
 * memory snapshot layout only.</p>
 */
public interface DrawAPI {

    /**
     * Debug-drawing facade. Singleton per {@code GameAPI}; the entry point a
     * script uses.
     *
     * <pre>{@code
     * Draw draw = api.draw();
     * draw.rect("target-box", x, y, w, h).color(Colors.GREEN).thickness(2).ttl(1200).submit();
     * }</pre>
     */
    Draw draw();

    /**
     * Set one command ({@code debug_draw_set}), answering the key it is stored
     * under. One round-trip; prefer {@link Draw#frame()} for more than a couple of
     * commands in the same tick.
     *
     * @throws RuntimeException the transport's unchecked RPC error when the
     *         producer refuses the command — a full store, an exhausted text or
     *         polyline slot, an out-of-range coordinate, or world space
     */
    String drawSet(DrawCommand command);

    /**
     * Set many commands in one round-trip ({@code debug_draw_set_batch}).
     *
     * <p><b>This call reports per-item failure as success.</b> The producer answers
     * a normal result carrying the number applied, the number refused and the
     * first error string only; a batch that drew nothing still looks like a
     * successful call at the envelope level. Read
     * {@link DrawBatchResult#dropped()}.</p>
     *
     * <p>A list longer than
     * {@link DrawLimits#MAX_BATCH_ITEMS} is a hard
     * error on the producer, not a truncation, and it loses the whole batch —
     * {@link DrawFrame} splits rather than risk that.</p>
     */
    DrawBatchResult drawSetBatch(List<DrawCommand> commands);

    /** Remove the named commands ({@code debug_draw_clear}), answering how many went. */
    int drawClear(List<String> keys);

    /**
     * Remove everything this connection drew ({@code debug_draw_clear_all}),
     * answering how many went. Deliberately never widened to the producer's
     * {@code scope: "all"}: a script must not be able to erase another client's
     * drawings.
     */
    int drawClearAll();

    /**
     * Every command this connection currently holds ({@code debug_draw_list}).
     *
     * <p>The wire is paged at 128 rows, so this costs one round-trip per 128
     * retained commands. Note {@link DrawEntry#remainingTtlMs()} is what is left,
     * not what you sent.</p>
     */
    List<DrawEntry> drawList();

    /** Whether the producer is currently drawing at all ({@code debug_draw_enable}). */
    boolean isDrawEnabled();

    /**
     * Switch drawing on or off ({@code debug_draw_enable}), answering the state
     * that took effect. Process-wide on the producer, not per connection.
     */
    boolean setDrawEnabled(boolean enabled);

    /** The producer's overlay counters and renderer health ({@code debug_draw_stats}). */
    DrawStats drawStats();
}
