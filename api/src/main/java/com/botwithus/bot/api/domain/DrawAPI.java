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
 *
 * <p><b>Migrating past the highlight binding.</b> Additive on the wire, but not on this
 * interface — binding the semantic highlights changed it in three ways, all of which break
 * a third-party <i>implementor</i> and none of which break a caller of
 * {@link #draw()}:</p>
 *
 * <ul>
 *   <li>{@link #drawSet} and {@link #drawSetBatch} narrowed their parameters to
 *       {@link DrawCommand.Primitive}. That narrowing is the point: it is what makes a
 *       batched highlight a compile error rather than a producer-side
 *       {@code unknown kind}.</li>
 *   <li>{@link #drawHighlight} and {@link #drawHighlights} are new abstract methods, so an
 *       existing implementation of this interface no longer compiles until it supplies
 *       them. {@code MockGameAPI} in {@code bot-test-support} shows the shape: throw, since
 *       a producer-side overlay has no in-memory analogue and a mock that silently "drew"
 *       nothing is worse than one that says so.</li>
 * </ul>
 *
 * <p>Scripts that only call {@code api.draw()} and the fluent surface behind it are
 * unaffected except for {@link com.botwithus.bot.api.draw.DrawTarget#npc}'s return type —
 * see its javadoc.</p>
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
    String drawSet(DrawCommand.Primitive command);

    /**
     * Set one highlight, answering the key it is stored under — one round-trip to
     * {@code highlight_entity}, {@code highlight_tile} or {@code highlight_area},
     * chosen from the command's own variant.
     *
     * <p>Separate from {@link #drawSet} because these are separate wire methods, and
     * the parameter type is {@link DrawCommand.Highlight} rather than
     * {@link DrawCommand} so the two cannot be crossed: a highlight has no
     * {@code kind} a {@code debug_draw_set} would accept, and a primitive has no
     * {@code highlight_*} call that would take it.</p>
     *
     * @throws RuntimeException the transport's unchecked RPC error when the producer
     *         refuses the highlight — a full store, an out-of-range tile, or an
     *         entity reference it will not take
     */
    String drawHighlight(DrawCommand.Highlight highlight);

    /**
     * Set several highlights, answering the aggregate the way a batch does.
     *
     * <p><b>This is N round-trips, not one.</b> There is no highlight batch on the
     * wire: {@code debug_draw_set_batch} applies {@code debug_draw_set} items, whose
     * {@code kind} field cannot name a semantic highlight, so one call per highlight
     * is the only shape available. It is here as the path {@link DrawFrame} takes,
     * and it is named plurally rather than {@code drawHighlightBatch} so nobody
     * reads it as a wire primitive it is not.</p>
     *
     * <p>It reports refusals the way a batch does — counted into
     * {@link DrawBatchResult#dropped()} with the first message kept — rather than
     * throwing, because that is the contract a frame's caller already has and a
     * debug overlay must not be able to take a script's tick down. <b>A transport
     * failure still propagates</b>, because a dead pipe is not the overlay failing
     * and swallowing it would hide it. Unlike a batch, each highlight is a single
     * store operation, so every one before a propagated failure is applied and the
     * failing one is not — there is no half-applied call.</p>
     */
    DrawBatchResult drawHighlights(List<DrawCommand.Highlight> highlights);

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
     *
     * <p><b>An exception from this call means the store is in an unknown state,
     * not a clean one.</b> The producer applies each item as it walks the array,
     * so an envelope-level failure part-way through leaves everything before it
     * already drawn, and the error reply carries no count. The two exceptions are
     * an over-size {@code items} array, which is refused before the walk begins,
     * and a failure that closes the pipe, after which the producer drops
     * everything this connection drew. {@link Draw#list()} is the way to find out
     * what is actually retained.</p>
     *
     * <p>The parameter is {@code List<}{@link DrawCommand.Primitive}{@code >} rather
     * than {@code List<DrawCommand>} deliberately: a {@link DrawCommand.Highlight}
     * cannot be applied by a batch item, so the type system refuses one here rather
     * than the producer refusing it as {@code unknown kind} after the round-trip.
     * {@link #drawHighlights} is where those go.</p>
     */
    DrawBatchResult drawSetBatch(List<DrawCommand.Primitive> commands);

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
