package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawBatchResult;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawEntry;
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
    private static final String POINTS = "points";
    private static final String IFACE = "iface";
    private static final String COMP = "comp";

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
    static Map<String, Object> encode(DrawCommand command) {
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

    private static void putGeometry(DrawCommand command, Map<String, Object> params) {
        switch (command) {
            case DrawCommand.Line line -> {
                params.put("x1", line.x1());
                params.put("y1", line.y1());
                params.put("x2", line.x2());
                params.put("y2", line.y2());
            }
            case DrawCommand.Rect rect -> putBox(params, rect.x(), rect.y(), rect.w(), rect.h());
            case DrawCommand.Ellipse e -> putBox(params, e.x(), e.y(), e.w(), e.h());
            case DrawCommand.Text text -> {
                params.put("x", text.x());
                params.put("y", text.y());
                params.put(TEXT, text.text());
            }
            case DrawCommand.Poly poly -> params.put(POINTS, poly.points());
            case DrawCommand.ComponentTarget target -> {
                params.put(IFACE, target.interfaceId());
                params.put(COMP, target.componentId());
            }
        }
    }

    private static void putBox(Map<String, Object> params, int x, int y, int w, int h) {
        params.put("x", x);
        params.put("y", y);
        params.put("w", w);
        params.put("h", h);
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
                getString(row, TEXT));
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
