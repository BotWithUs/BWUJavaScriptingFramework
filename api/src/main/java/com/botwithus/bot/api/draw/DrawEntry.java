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
 * <p><b>{@link #isResolved()} means two different things and this row does not say
 * which.</b> Read it against {@link #kind()} and {@link #space()}, never on its own:</p>
 *
 * <ul>
 *   <li>For a <b>screen-space primitive</b> — a rect, line, ellipse, poly or text in
 *       {@link DrawSpace#SCREEN} — it is permanently {@code false}, and the command is
 *       perfectly fine. Nothing was resolved because nothing needed resolving: the
 *       coordinates were already pixels. A consumer that reads {@code false} here as "my
 *       drawing is broken" will be wrong about every screen command it ever sends.</li>
 *   <li>For a <b>component highlight</b>, and for anything in {@link DrawSpace#WORLD} —
 *       which includes every {@link DrawKind#ENTITY} and {@link DrawKind#TILE} row, since
 *       the semantic highlights are world-space by construction — it <i>is</i> a health
 *       bit. {@code false} means the producer has no usable screen position for what the
 *       command marks: no scene, no window, behind the camera, or a plane it cannot
 *       height. Nothing is drawn, and {@link #rect()} is <b>zeroed</b> rather than left
 *       holding the last good value, so a stale-but-plausible rectangle can never be read
 *       back at the moment a marker goes invalid.</li>
 * </ul>
 *
 * <p>Two further traps on the world side. {@code isResolved() == true} does not promise a
 * paintable area — a footprint far enough away that all four projected corners round to
 * one pixel, and every {@code text} command, legitimately report a zero extent — so code
 * that needs somewhere to draw must check the extent, not only the flag. And the flag
 * cannot distinguish "outside the viewport but real and clampable" from "behind the
 * camera, no usable position exists"; the producer's {@code project} field carries that
 * distinction in five states and this record does not surface it yet.</p>
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
 * @param isResolved      whether the producer currently holds a screen position for what
 *                        this command marks. <b>Permanently false, and harmless, for a
 *                        screen-space primitive</b>; a health bit only for a component
 *                        highlight and for world-space commands, entity and tile
 *                        highlights included. See the class javadoc before branching on
 *                        it
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
