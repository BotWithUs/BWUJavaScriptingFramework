package com.botwithus.bot.api.draw;

import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Player;

import java.util.List;

/**
 * Somewhere a draw command can be sent — either straight down the wire
 * ({@link Draw}) or into a batch ({@link DrawFrame}).
 *
 * <p>The primitives live here as defaults so the two destinations cannot drift
 * apart: {@code draw.rect(...)} and {@code frame.rect(...)} are the same code,
 * differing only in where {@link #submit(DrawCommand)} puts the result.</p>
 */
public sealed interface DrawTarget permits Draw, DrawFrame {

    /**
     * Route a fully-built command, answering the key it is stored under.
     *
     * <p>The plumbing seam the fluent builders call, and the escape hatch for a
     * caller holding a {@link DrawCommand} it built or stored itself.</p>
     */
    String submit(DrawCommand command);

    /** Straight segment from {@code (x1, y1)} to {@code (x2, y2)}. */
    default DrawBuilder line(String key, int x1, int y1, int x2, int y2) {
        return new DrawBuilder(this,
                (space, style) -> new DrawCommand.Line(key, space, style, x1, y1, x2, y2));
    }

    /** Axis-aligned rectangle. Stroked unless {@link DrawBuilder#filled()}. */
    default DrawBuilder rect(String key, int x, int y, int w, int h) {
        return new DrawBuilder(this,
                (space, style) -> new DrawCommand.Rect(key, space, style, x, y, w, h));
    }

    /** Ellipse inscribed in the given rectangle. */
    default DrawBuilder ellipse(String key, int x, int y, int w, int h) {
        return new DrawBuilder(this,
                (space, style) -> new DrawCommand.Ellipse(key, space, style, x, y, w, h));
    }

    /**
     * A caption anchored at {@code (x, y)}, drawn as-is.
     *
     * <p>Returns a {@link DrawCaptionBuilder}, so {@code font(...)} and
     * {@code value(...)} are reachable here and nowhere a shape can see them.</p>
     */
    default DrawCaptionBuilder text(String key, int x, int y, String text) {
        return captioned(key, x, y, DrawCaption.of(text));
    }

    /**
     * A scaled number anchored at {@code (x, y)}:
     * {@code value(key, x, y, 1234, 2)} draws {@code "12.34"}.
     *
     * <p>There is no float anywhere on this wire, deliberately. Send a scaled
     * integer and say what you scaled it by.</p>
     */
    default DrawCaptionBuilder value(String key, int x, int y, long value, int decimals) {
        return captioned(key, x, y, DrawCaption.of(value, decimals));
    }

    private DrawCaptionBuilder captioned(String key, int x, int y, DrawCaption caption) {
        return new DrawCaptionBuilder(this, DrawBuilder.styleOnly(this),
                (space, style, words) -> new DrawCommand.Text(key, space, style, x, y, words),
                caption);
    }

    /** Polyline over a flat {@code [x, y, x, y, ...]} list. */
    default DrawBuilder poly(String key, List<Integer> points) {
        return new DrawBuilder(this,
                (space, style) -> new DrawCommand.Poly(key, space, style, points));
    }

    /**
     * Highlight an interface component under a key you choose. The producer
     * re-resolves the rect every tick, so the highlight follows the component
     * rather than drifting when the UI relays out.
     */
    default DrawCaptionBuilder component(String key, int interfaceId, int componentId) {
        return new DrawCaptionBuilder(this, DrawBuilder.styleOnly(this),
                (space, style, caption) -> new DrawCommand.ComponentTarget(
                        key, space, style, interfaceId, componentId, caption),
                DrawCaption.NONE);
    }

    /**
     * Highlight an interface component under the conventional auto key
     * {@code comp:<iface>:<comp>}.
     *
     * <p>The key is computed here rather than left to the producer, so it is
     * known before the command is sent and {@link DrawBuilder#submit()} can
     * answer it. A caller who never learns the key cannot clear the highlight;
     * {@link DrawCommand.ComponentTarget#autoKey(int, int)} is the same format,
     * exposed for anyone predicting it.</p>
     */
    default DrawCaptionBuilder component(int interfaceId, int componentId) {
        return component(DrawCommand.ComponentTarget.autoKey(interfaceId, componentId),
                interfaceId, componentId);
    }

    // ---------------------------------------------------------------- highlights
    //
    // The four calls that mark a THING rather than a place. Each is its own
    // highlight_* round-trip and none of them can ride debug_draw_set_batch — see
    // DrawCommand.Highlight for why, and DrawFrame.flush() for what a frame does
    // with one.

    /**
     * Highlight an NPC, tracked. The box follows the NPC as it walks, and sits at
     * the NPC's own height rather than the local player's.
     *
     * <p><b>This is the call the older, worse version of this helper was a
     * placeholder for.</b> It used to send a world-space rect at the tile the NPC
     * occupied when it was called, which marked a <i>position</i>: the box stayed
     * put once the NPC moved, and it carried no plane, so an NPC upstairs was
     * marked on the ground floor. Its javadoc promised that binding the producer's
     * semantic highlight would "change what this sends rather than how it is
     * called". This is that change, and the promise held for the name and the
     * arguments.</p>
     *
     * <p><b>Migrating: the return type moved from {@link DrawBuilder} to
     * {@link HighlightBuilder}.</b> A chained call is unaffected —
     * {@code draw.npc(key, npc).color(...).ttl(...).submit()} compiles and reads
     * exactly as before. What no longer compiles is assigning the result to a
     * {@code DrawBuilder} variable, and calling {@code screen()}, {@code world()} or
     * {@code closed()} on it: a highlight's space is not a caller's choice, so
     * offering it would be offering a decision that does not exist. Where you held
     * the builder in a local, change its type; the fluent methods you were calling
     * are all still there.</p>
     *
     * <p>Footprint defaults to one tile. Use
     * {@link #npc(String, Npc, int, int)} for a big NPC, or the box will sit inside
     * it.</p>
     */
    default HighlightBuilder npc(String key, Npc npc) {
        return entity(key, EntityRef.NPC, npc.serverIndex());
    }

    /** Highlight an NPC, tracked, with a footprint in <b>tiles</b>. */
    default HighlightBuilder npc(String key, Npc npc, int widthTiles, int heightTiles) {
        return entity(key, EntityRef.NPC, npc.serverIndex(), widthTiles, heightTiles);
    }

    /**
     * Highlight an NPC under the conventional auto key {@code npc:<index>}.
     *
     * <p>The key is computed here rather than left to the producer, so it is known
     * before the command is sent and {@link HighlightBuilder#submit()} can answer
     * it — a caller who never learns the key cannot clear the highlight.
     * {@link DrawCommand.Entity#autoKey(EntityRef, int)} is the same format, exposed
     * for anyone predicting it.</p>
     */
    default HighlightBuilder npc(Npc npc) {
        return npc(DrawCommand.Entity.autoKey(EntityRef.NPC, npc.serverIndex()), npc);
    }

    /** Highlight another player, tracked. Same contract as {@link #npc(String, Npc)}. */
    default HighlightBuilder player(String key, Player player) {
        return entity(key, EntityRef.PLAYER, player.serverIndex());
    }

    /** Highlight another player under the conventional auto key {@code player:<index>}. */
    default HighlightBuilder player(Player player) {
        return player(DrawCommand.Entity.autoKey(EntityRef.PLAYER, player.serverIndex()),
                player);
    }

    /**
     * Highlight the local player, tracked.
     *
     * <p>Takes no index and stores none: the producer re-resolves the local player's
     * list slot every tick, so this survives a world hop that would leave a stored
     * index pointing at whoever took the seat.</p>
     */
    default HighlightBuilder self(String key) {
        return entity(key, EntityRef.SELF, 0);
    }

    /** Highlight the local player under the conventional auto key {@code self}. */
    default HighlightBuilder self() {
        return self(DrawCommand.Entity.autoKey(EntityRef.SELF, 0));
    }

    /**
     * Highlight an entity by list and server index — the general form the three
     * named entity helpers funnel through, for a caller holding an index without a
     * snapshot row to go with it.
     *
     * <p>Exactly one list is named, which is why {@link EntityRef} is a parameter
     * rather than two nullable ones: the producer refuses both {@code npc} and
     * {@code player} together and refuses neither, because "which wins" is not a
     * rule a caller could guess from a reply that succeeded. NPC 42 and player 42
     * are different entities.</p>
     */
    default HighlightBuilder entity(String key, EntityRef ref, int serverIndex) {
        return entity(key, ref, serverIndex,
                DrawLimits.DEFAULT_FOOTPRINT_TILES, DrawLimits.DEFAULT_FOOTPRINT_TILES);
    }

    /** {@link #entity(String, EntityRef, int)} with a footprint in <b>tiles</b>. */
    default HighlightBuilder entity(String key, EntityRef ref, int serverIndex,
                                    int widthTiles, int heightTiles) {
        return new HighlightBuilder(this, (style, caption) -> new DrawCommand.Entity(
                key, style, ref, serverIndex, widthTiles, heightTiles, caption));
    }

    /**
     * Highlight one game tile on the ground floor, in whole <b>tiles</b>.
     *
     * <p>Kept at this signature because it already had it — this is the same call,
     * now sending {@code highlight_tile} instead of a world-space rect, which is
     * what its old javadoc said binding the highlight would do. It still lands on
     * plane 0 for the same reason it did before: no plane was ever sent. Prefer
     * {@link #tile(String, int, int, int)} and pass the plane you mean.</p>
     *
     * <p>Note the unit change against the primitives: this takes <b>tiles</b>, while
     * {@code rect(...).world()} at the same place takes
     * {@code tile * }{@link DrawLimits#SUBTILE_SCALE}. That split is the producer's
     * and its handler is the one place the two meet.</p>
     */
    default HighlightBuilder tile(String key, int tileX, int tileY) {
        return tile(key, tileX, tileY, DrawLimits.MIN_PLANE);
    }

    /**
     * Highlight one game tile on a named plane, in whole <b>tiles</b>.
     *
     * <p><b>A plane other than the one the local player is standing on draws
     * nothing.</b> Not an error — the call is accepted and stored — but it resolves
     * {@code unavailable}, because the producer's only ground-height source is the
     * player's own elevation and it refuses rather than placing a marker
     * convincingly wrong on the player's floor. {@link #npc(String, Npc)} and the
     * other entity highlights are the exemption, because an entity carries its own
     * height. See {@link DrawCommand.Tile}.</p>
     */
    default HighlightBuilder tile(String key, int tileX, int tileY, int plane) {
        return new HighlightBuilder(this, (style, caption) ->
                new DrawCommand.Tile(key, style, tileX, tileY, plane, caption));
    }

    /**
     * Highlight one game tile under the conventional auto key
     * {@code tile:<x>:<y>:<plane>}.
     */
    default HighlightBuilder tile(int tileX, int tileY, int plane) {
        return tile(DrawCommand.Tile.autoKey(tileX, tileY, plane), tileX, tileY, plane);
    }

    /**
     * Highlight a {@code widthTiles} by {@code heightTiles} block of ground,
     * anchored at its minimum corner, all in whole <b>tiles</b>.
     *
     * <p>The separate call rather than a {@code w}/{@code h} on {@link #tile}, because
     * {@code highlight_tile} <b>refuses</b> an extent rather than dropping one — a
     * silently-ignored {@code w} is how a caller ends up convinced a 5x5 highlight
     * rendered as 1x1 because of a projection bug. Carries the same plane rule as
     * {@link #tile(String, int, int, int)}.</p>
     */
    default HighlightBuilder area(String key, int tileX, int tileY,
                                  int widthTiles, int heightTiles, int plane) {
        return new HighlightBuilder(this, (style, caption) -> new DrawCommand.Area(
                key, style, tileX, tileY, widthTiles, heightTiles, plane, caption));
    }

    /**
     * Highlight a block of ground under the conventional auto key
     * {@code area:<x>:<y>:<w>:<h>:<plane>}.
     */
    default HighlightBuilder area(int tileX, int tileY, int widthTiles, int heightTiles,
                                  int plane) {
        return area(DrawCommand.Area.autoKey(tileX, tileY, widthTiles, heightTiles, plane),
                tileX, tileY, widthTiles, heightTiles, plane);
    }
}
