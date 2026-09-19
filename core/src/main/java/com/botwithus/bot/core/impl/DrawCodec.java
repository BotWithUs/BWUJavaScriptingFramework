package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCaption;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawEntry;
import com.botwithus.bot.api.draw.DrawFont;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.DrawStats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getInt;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getLong;
import static com.botwithus.bot.core.impl.MapHelper.getString;

/**
 * Wire encoding for the {@code debug_draw_*} method group.
 *
 * <p>Producer-side coupling: every key written here is read by {@code MatchDrawParam}
 * in {@code NXTLibrary/src/rpc/Handlers.cpp}, and every key read here is written by
 * {@code WriteDrawItem} / {@code Handle_DebugDrawStats} in the same file. A rename on
 * either side needs the other in the same logical change. The group is additive over
 * the RPC pipe and does not move {@code Layout.PROTOCOL_VERSION}.</p>
 */
final class DrawCodec {

    private static final String KEY = "key";
    private static final String KIND = "kind";
    private static final String SPACE = "space";
    private static final String COLOR = "color";
    private static final String THICKNESS = "thickness";
    private static final String FILLED = "filled";
    private static final String CLOSED = "closed";
    private static final String Z = "z";
    private static final String TTL_MS = "ttl_ms";
    private static final String TEXT = "text";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String W = "w";
    private static final String H = "h";
    private static final String PLANE = "plane";
    private static final String POINTS = "points";
    private static final String IFACE = "iface";
    private static final String COMP = "comp";
    private static final String LABEL = "label";
    private static final String VALUE = "value";
    private static final String DECIMALS = "decimals";
    private static final String FONT = "font";

    private DrawCodec() {
    }

    /**
     * One command as the producer's parameter map.
     *
     * <p>{@code space} and {@code ttl_ms} are always written, even at their
     * defaults, so the lifetime and space a command actually got are readable back
     * out of {@code debug_draw_list} rather than being invisible producer-side
     * behaviour.</p>
     */
    static Map<String, Object> encode(DrawCommand.Primitive command) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(KEY, command.key());
        params.put(KIND, command.kind().wireName());
        params.put(SPACE, command.space().wireName());
        params.put(COLOR, command.style().color());
        params.put(THICKNESS, command.style().thickness());
        params.put(FILLED, command.style().isFilled());
        params.put(CLOSED, command.style().isClosed());
        params.put(Z, command.style().z());
        params.put(TTL_MS, command.style().ttlMs());
        putGeometry(command, params);
        return params;
    }

    /**
     * The {@code highlight_*} method one highlight rides.
     *
     * <p>Chosen from the variant rather than stored on it, so the method name lives
     * beside every other wire string in this file. Note {@code highlight_tile} and
     * {@code highlight_area} are two methods over one producer-side kind — which is
     * why {@link DrawCommand.Area#kind()} reports {@link DrawKind#TILE} and the method
     * cannot be derived from the kind.</p>
     */
    static String highlightMethod(DrawCommand.Highlight highlight) {
        return switch (highlight) {
            case DrawCommand.Entity ignored -> "highlight_entity";
            case DrawCommand.Tile ignored -> "highlight_tile";
            case DrawCommand.Area ignored -> "highlight_area";
        };
    }

    /**
     * One highlight as its handler's parameter map.
     *
     * <p><b>No {@code kind} and no {@code space}.</b> The handler forces both — it
     * sets the kind itself because {@code entity} and {@code tile} are deliberately
     * unspellable, and overwrites {@code space} with {@code world} before validating
     * anything. Sending either would be noise the producer discards, and sending a
     * {@code space} would read like a choice the caller does not have.</p>
     *
     * <p>No {@code closed} either: that is a polyline's flag, and a highlight is never
     * a polyline.</p>
     *
     * <p><b>Coordinates and extents here are whole TILES</b>, not the sub-tiles a
     * {@code space: "world"} primitive sends. The handler is the single place the two
     * units meet, so nothing downstream of it ever sees both.</p>
     */
    static Map<String, Object> encodeHighlight(DrawCommand.Highlight highlight) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(KEY, highlight.key());
        params.put(COLOR, highlight.style().color());
        params.put(THICKNESS, highlight.style().thickness());
        params.put(FILLED, highlight.style().isFilled());
        params.put(Z, highlight.style().z());
        params.put(TTL_MS, highlight.style().ttlMs());
        putHighlightTarget(highlight, params);
        putCaption(params, highlight.caption(), LABEL);
        return params;
    }

    /**
     * What the highlight marks.
     *
     * <p><b>An entity sends no {@code plane}</b>, deliberately. It is the one world
     * kind that carries its own height, so the producer resolves it from the entity's
     * live scene position and never consults {@code plane} — sending one would be a
     * parameter accepted and ignored, which is the specific mistake the rest of this
     * wire's design goes out of its way to avoid.</p>
     *
     * <p>{@code self} is a bool and is written only when true: a {@code self: false}
     * is read as a named reference by the producer's counter and would collide with
     * {@code npc} or {@code player} rather than being ignored.</p>
     */
    private static void putHighlightTarget(DrawCommand.Highlight highlight,
                                           Map<String, Object> params) {
        switch (highlight) {
            case DrawCommand.Entity entity -> {
                if (entity.ref().hasIndex()) {
                    params.put(entity.ref().wireName(), entity.serverIndex());
                } else {
                    params.put(entity.ref().wireName(), true);
                }
                params.put(W, entity.widthTiles());
                params.put(H, entity.heightTiles());
            }
            case DrawCommand.Tile tile -> {
                params.put(X, tile.tileX());
                params.put(Y, tile.tileY());
                params.put(PLANE, tile.plane());
            }
            case DrawCommand.Area area -> {
                params.put(X, area.tileX());
                params.put(Y, area.tileY());
                params.put(W, area.widthTiles());
                params.put(H, area.heightTiles());
                params.put(PLANE, area.plane());
            }
        }
    }

    private static void putGeometry(DrawCommand.Primitive command, Map<String, Object> params) {
        switch (command) {
            case DrawCommand.Line line -> {
                params.put(X + "1", line.x1());
                params.put(Y + "1", line.y1());
                params.put(X + "2", line.x2());
                params.put(Y + "2", line.y2());
            }
            case DrawCommand.Rect rect -> putBox(params, rect.x(), rect.y(), rect.w(), rect.h());
            case DrawCommand.Ellipse e -> putBox(params, e.x(), e.y(), e.w(), e.h());
            case DrawCommand.Text text -> {
                params.put(X, text.x());
                params.put(Y, text.y());
                putCaption(params, text.caption(), TEXT);
            }
            case DrawCommand.Poly poly -> params.put(POINTS, poly.points());
            case DrawCommand.ComponentTarget target -> {
                params.put(IFACE, target.interfaceId());
                params.put(COMP, target.componentId());
                putCaption(params, target.caption(), LABEL);
            }
        }
    }

    /**
     * The caption, under whichever of the wire's three spellings applies.
     *
     * <p>{@code text} and {@code label} are two names for one producer-side
     * buffer and a kind accepts exactly one of them — a {@code text} on a
     * component is rejected with
     * {@code component names its caption "label", not "text"} — so the caller's
     * kind picks the key. {@code value} is the third spelling and cannot be
     * combined with either; a sealed {@link DrawCaption} is what makes combining
     * them unrepresentable rather than merely refused.</p>
     *
     * <p>Nothing is written for {@link DrawCaption.None}, and {@code font} rides
     * with the caption rather than with the style — the producer rejects a
     * {@code font} on a kind that draws no words, so it must never appear on a
     * shape.</p>
     */
    private static void putCaption(Map<String, Object> params, DrawCaption caption, String key) {
        switch (caption) {
            case DrawCaption.Literal literal -> {
                params.put(key, literal.text());
                params.put(FONT, literal.font().wireName());
            }
            case DrawCaption.FixedPoint fixed -> {
                params.put(VALUE, fixed.value());
                params.put(DECIMALS, fixed.decimals());
                params.put(FONT, fixed.font().wireName());
            }
            case DrawCaption.None ignored -> {
                // No caption: send neither a payload nor a font.
            }
        }
    }

    private static void putBox(Map<String, Object> params, int x, int y, int w, int h) {
        params.put(X, x);
        params.put(Y, y);
        params.put(W, w);
        params.put(H, h);
    }

    /**
     * A {@code debug_draw_set_batch} reply.
     *
     * <p>The reply is a success envelope whatever happened, so {@code dropped} is
     * the only signal that part of the batch was refused, and {@code error} carries
     * the first refusal's message only.</p>
     */
    static DrawBatchResult decodeBatch(Map<String, Object> reply) {
        return new DrawBatchResult(getInt(reply, "count"), getInt(reply, "dropped"),
                getString(reply, "error"));
    }

    /** One row of a {@code debug_draw_list} page. */
    static DrawEntry decodeEntry(Map<String, Object> row) {
        return new DrawEntry(
                getString(row, KEY),
                DrawKind.fromWireName(getString(row, KIND)),
                DrawSpace.fromWireName(getString(row, SPACE)),
                getInt(row, COLOR),
                getInt(row, THICKNESS),
                getInt(row, Z),
                getBool(row, FILLED),
                getBool(row, CLOSED),
                getIntList(row, "geom"),
                getBool(row, "resolved"),
                getIntList(row, "rect"),
                getLong(row, TTL_MS),
                getString(row, TEXT),
                DrawFont.fromWireName(getString(row, FONT)));
    }

    /** A {@code debug_draw_stats} reply. */
    static DrawStats decodeStats(Map<String, Object> reply) {
        List<Integer> surface = getIntList(reply, "surface");
        return new DrawStats(
                getInt(reply, "count"),
                getInt(reply, "capacity"),
                getInt(reply, "text_slots_used"),
                getInt(reply, "text_slots_capacity"),
                getInt(reply, "poly_slots_used"),
                getInt(reply, "poly_slots_capacity"),
                getInt(reply, "live_targets"),
                getLong(reply, "dropped"),
                getLong(reply, "resolve_failures"),
                getLong(reply, "resolve_overflow"),
                getBool(reply, "enabled"),
                getBool(reply, "has_presented"),
                getLong(reply, "present_failures"),
                getBool(reply, "resolver_live"),
                getLong(reply, "backend_presents"),
                getString(reply, "backend"),
                axis(surface, 0),
                axis(surface, 1));
    }

    private static int axis(List<Integer> surface, int index) {
        return index < surface.size() ? surface.get(index) : 0;
    }
}
