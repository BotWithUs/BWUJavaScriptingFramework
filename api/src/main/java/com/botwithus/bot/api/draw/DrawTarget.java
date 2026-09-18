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

    /** A string anchored at {@code (x, y)}. */
    default DrawBuilder text(String key, int x, int y, String text) {
        return new DrawBuilder(this,
                (space, style) -> new DrawCommand.Text(key, space, style, x, y, text));
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
    default DrawBuilder component(String key, int interfaceId, int componentId) {
        return new DrawBuilder(this, (space, style) ->
                new DrawCommand.ComponentTarget(key, space, style, interfaceId, componentId));
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
    default DrawBuilder component(int interfaceId, int componentId) {
        return component(DrawCommand.ComponentTarget.autoKey(interfaceId, componentId),
                interfaceId, componentId);
    }

    /**
     * A world-space box over an NPC's tile.
     *
     * <p><b>This fails today.</b> World space is accepted and validated by the
     * producer and then rejected with
     * {@code "world space requires projection - not yet implemented"}; the
     * projection is a later phase. The signature ships now so that the code a
     * script writes today is the code that works when projection lands.</p>
     *
     * <p>The fixed-point encoding of the box itself is provisional and may be
     * refined when the producer defines world geometry precisely; the shape of
     * this call will not.</p>
     */
    default DrawBuilder npc(String key, Npc npc) {
        return tile(key, npc.tileX(), npc.tileY()).world();
    }

    /**
     * A world-space box over a player's tile. Fails today for the same reason as
     * {@link #npc(String, Npc)}.
     */
    default DrawBuilder player(String key, Player player) {
        return tile(key, player.tileX(), player.tileY()).world();
    }

    /**
     * A world-space box over one game tile, in fixed point
     * ({@link DrawLimits#SUBTILE_SCALE} sub-tiles per tile). Fails today for the
     * same reason as {@link #npc(String, Npc)}.
     */
    default DrawBuilder tile(String key, int tileX, int tileY) {
        return rect(key,
                tileX * DrawLimits.SUBTILE_SCALE, tileY * DrawLimits.SUBTILE_SCALE,
                DrawLimits.SUBTILE_SCALE, DrawLimits.SUBTILE_SCALE).world();
    }
}
