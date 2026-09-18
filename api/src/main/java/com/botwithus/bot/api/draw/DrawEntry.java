package com.botwithus.bot.api.draw;

import java.util.List;

/**
 * One retained draw command as the producer currently holds it.
 *
 * <p>Answered by {@link Draw#list()}. This is the producer's view, not an echo of
 * what you sent, and two fields make that difference load-bearing:</p>
 *
 * <ul>
 *   <li>{@link #remainingTtlMs()} is what is <b>left</b>, not what you asked for.
 *       Round-tripping a TTL through this is not symmetric — a command sent with
 *       {@code ttl(3000)} reports something under 3000 a moment later, and
 *       {@code -1} when it was sent with {@link DrawLimits#TTL_UNTIL_CLEARED}.</li>
 *   <li>{@link #rect()} on a {@link DrawKind#COMPONENT} entry is the last rect the
 *       game thread resolved, in the client's <b>layout</b> units rather than
 *       surface pixels — the same units {@code get_component} reports. Anyone
 *       comparing it against a screenshot needs to know the producer scales it by
 *       surface/layout before drawing. It is meaningful only when
 *       {@link #isResolved()}.</li>
 * </ul>
 *
 * @param key             the command's key within its connection
 * @param kind            the primitive, or {@code null} if this build does not know the
 *                        name the producer used
 * @param space           coordinate space
 * @param color           packed {@code 0xAARRGGBB}
 * @param thickness       stroke width
 * @param z               draw order
 * @param isFilled        filled rather than stroked
 * @param isClosed        polyline closed
 * @param geometry        the command's four raw geometry slots, as the producer stores them
 * @param isResolved      a component target whose rect is current
 * @param rect            the last resolved rect {@code [x, y, w, h]}, in layout units
 * @param remainingTtlMs  milliseconds left, or {@code -1} for no expiry
 * @param text            the words this entry draws. Populated for a
 *                        {@link DrawKind#TEXT} entry and — since the producer gained
 *                        captions — for a {@link DrawKind#COMPONENT} entry too, where
 *                        it carries the highlight's label. Empty when the entry has no
 *                        caption. A fixed-point caption arrives already formatted,
 *                        because the producer does the formatting and this wire has no
 *                        float to send back.
 * @param font            the style the caption is drawn in; {@link DrawFont#NORMAL}
 *                        when the entry has no caption
 */
public record DrawEntry(String key, DrawKind kind, DrawSpace space, int color, int thickness,
                        int z, boolean isFilled, boolean isClosed, List<Integer> geometry,
                        boolean isResolved, List<Integer> rect, long remainingTtlMs,
                        String text, DrawFont font) {

    /** Milliseconds reported for a command that never expires. */
    public static final long NO_EXPIRY = -1L;

    public DrawEntry {
        geometry = geometry == null ? List.of() : List.copyOf(geometry);
        rect = rect == null ? List.of() : List.copyOf(rect);
        text = text == null ? "" : text;
        font = font == null ? DrawFont.NORMAL : font;
    }

    /** True when this command has no expiry and lives until replaced or cleared. */
    public boolean isPersistent() {
        return remainingTtlMs == NO_EXPIRY;
    }
}
