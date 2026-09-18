package com.botwithus.bot.api.draw;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One fully-specified draw command, ready for the wire.
 *
 * <p>Sealed, with one variant per geometry shape, so the encoder behind
 * {@link com.botwithus.bot.api.domain.DrawAPI} dispatches through an exhaustive
 * {@code switch} and a new primitive cannot be added without every encoder being
 * told about it.</p>
 *
 * <p><b>Commands are keyed, not handled.</b> The key is a caller-chosen string of
 * 1 to {@link DrawLimits#MAX_KEY_BYTES} UTF-8 bytes; setting the same key again
 * replaces rather than appends, so redrawing {@code "target-box"} every tick is
 * idempotent. There is no handle to leak and nothing to free. Keys are scoped to
 * the connection: two scripts on two clients may both use {@code "target-box"}
 * without colliding, and the producer drops everything a connection drew when its
 * pipe closes.</p>
 */
public sealed interface DrawCommand {

    /** The caller-chosen identity of this command within its connection. */
    String key();

    /** The coordinate space this command's geometry is expressed in. */
    DrawSpace space();

    /** Colour, stroke, fill, order and lifetime. */
    DrawStyle style();

    /** The primitive this command draws. */
    DrawKind kind();

    /**
     * The key, validated.
     *
     * <p>Rejected here rather than on the wire so a script author gets the failure
     * at the call site with a stack trace, instead of a {@code dropped} count from
     * a batch that is already gone.</p>
     *
     * <p>The producer measures a key in UTF-8 bytes, so that is what is measured
     * here; {@code String.length()} would wave through keys the producer refuses.</p>
     */
    static String requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("a draw key must be a non-empty string");
        }
        int bytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > DrawLimits.MAX_KEY_BYTES) {
            throw new IllegalArgumentException("draw key exceeds "
                    + DrawLimits.MAX_KEY_BYTES + " UTF-8 bytes (" + bytes + "): " + key);
        }
        return key;
    }

    /** Straight segment from {@code (x1, y1)} to {@code (x2, y2)}. */
    record Line(String key, DrawSpace space, DrawStyle style,
                int x1, int y1, int x2, int y2) implements DrawCommand {

        public Line {
            requireKey(key);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.LINE;
        }
    }

    /** Axis-aligned rectangle, stroked or filled per {@link DrawStyle#isFilled()}. */
    record Rect(String key, DrawSpace space, DrawStyle style,
                int x, int y, int w, int h) implements DrawCommand {

        public Rect {
            requireKey(key);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.RECT;
        }
    }

    /** Ellipse inscribed in the given rectangle, stroked or filled. */
    record Ellipse(String key, DrawSpace space, DrawStyle style,
                   int x, int y, int w, int h) implements DrawCommand {

        public Ellipse {
            requireKey(key);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.ELLIPSE;
        }
    }

    /**
     * A string anchored at {@code (x, y)}.
     *
     * <p>The length cap is in UTF-16 code units because that is the buffer the
     * producer converts into; text that will not fit is refused, not truncated.</p>
     */
    record Text(String key, DrawSpace space, DrawStyle style,
                int x, int y, String text) implements DrawCommand {

        public Text {
            requireKey(key);
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("a text command needs non-empty text");
            }
            if (text.length() > DrawLimits.MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("draw text exceeds "
                        + DrawLimits.MAX_TEXT_LENGTH + " UTF-16 units (" + text.length() + ")");
            }
        }

        @Override
        public DrawKind kind() {
            return DrawKind.TEXT;
        }
    }

    /** Polyline over a flat {@code [x, y, x, y, ...]} list of 2 to 32 pairs. */
    record Poly(String key, DrawSpace space, DrawStyle style,
                List<Integer> points) implements DrawCommand {

        private static final int VALUES_PER_POINT = 2;

        public Poly {
            requireKey(key);
            if (points == null || (points.size() % VALUES_PER_POINT) != 0) {
                throw new IllegalArgumentException(
                        "poly points must be a flat [x, y, x, y, ...] list");
            }
            int pairs = points.size() / VALUES_PER_POINT;
            if (pairs < DrawLimits.MIN_POLY_POINTS || pairs > DrawLimits.MAX_POLY_POINTS) {
                throw new IllegalArgumentException("poly needs "
                        + DrawLimits.MIN_POLY_POINTS + ".." + DrawLimits.MAX_POLY_POINTS
                        + " points, got " + pairs);
            }
            points = List.copyOf(points);
        }

        @Override
        public DrawKind kind() {
            return DrawKind.POLY;
        }
    }

    /**
     * A highlight that tracks an interface component rather than a rectangle.
     *
     * <p>Carries no geometry: the producer stores {@code (interfaceId, componentId)}
     * and re-resolves the on-screen rect on the game thread once per tick, because
     * the client recomputes a component's rect on every layout pass. A stored
     * rectangle would drift the moment the UI relaid out, the window resized, or a
     * scrollpane moved.</p>
     */
    record ComponentTarget(String key, DrawSpace space, DrawStyle style,
                           int interfaceId, int componentId) implements DrawCommand {

        /** Prefix of the conventional auto key, as the producer spells it. */
        private static final String AUTO_KEY_PREFIX = "comp:";

        public ComponentTarget {
            requireKey(key);
        }

        /**
         * The key a component highlight gets when the caller names none:
         * {@code comp:<iface>:<comp>}, for example {@code comp:1473:5}.
         *
         * <p>That format is part of the producer's contract, and it exists so a
         * caller who omitted a key still has a way to name the highlight in order
         * to clear it. This host computes it rather than reading it back, so the
         * key is known before the command is sent — but the producer echoes the
         * key it actually used on every reply, and a live test asserts the two
         * agree rather than assuming it.</p>
         */
        public static String autoKey(int interfaceId, int componentId) {
            return AUTO_KEY_PREFIX + interfaceId + ':' + componentId;
        }

        @Override
        public DrawKind kind() {
            return DrawKind.COMPONENT;
        }
    }
}
