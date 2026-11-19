package com.botwithus.bot.api.gameui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The properties {@code ui.create} and {@code ui.set} take, built per component
 * type so a property that does not apply to a type cannot be set on it.
 *
 * <p>Two things are unrepresentable rather than validated:</p>
 * <ul>
 *   <li><b>The interaction configs</b> ({@code op_config}, {@code drag_config},
 *       {@code target} and the rest). The agent refuses them on every agent
 *       component; this class has no way to send one.</li>
 *   <li><b>A percentage without its pixel value.</b> The agent wants each
 *       {@code *_pc} prop in the same call as its {@code x}/{@code y}/{@code width}/
 *       {@code height}, so the percentage overloads take both.</li>
 * </ul>
 *
 * <p>Ranges are the agent's to enforce (see {@link UiLimits}); an out-of-range value
 * fails the whole call with the agent's own message and applies nothing.</p>
 *
 * <p>A builder is mutable and not thread-safe. It is read when it is passed to a
 * call, so reusing one afterwards does not change a component already made.</p>
 *
 * @param <P> the concrete builder, so chained calls keep their type
 */
public abstract sealed class UiProps<P extends UiProps<P>>
        permits CanvasProps, LayerProps, LineProps, RectangleProps, TextProps {

    private final Map<String, Object> values = new LinkedHashMap<>();

    UiProps() {
    }

    abstract P self();

    final P put(String key, Object value) {
        values.put(key, value);
        return self();
    }

    /** Position on the x axis, in pixels, as {@code Component.SetX}. */
    public final P x(int x) {
        return put("x", x);
    }

    /** Position on the x axis: pixels plus a fraction of the parent's and of this component's width. */
    public final P x(int x, double parentPercent, double selfPercent) {
        put("x", x);
        put("x_parent_pc", parentPercent);
        return put("x_self_pc", selfPercent);
    }

    /** Position on the y axis, in pixels, as {@code Component.SetY}. */
    public final P y(int y) {
        return put("y", y);
    }

    /** Position on the y axis: pixels plus a fraction of the parent's and of this component's height. */
    public final P y(int y, double parentPercent, double selfPercent) {
        put("y", y);
        put("y_parent_pc", parentPercent);
        return put("y_self_pc", selfPercent);
    }

    /** Shorthand for {@code x(x).y(y)}. */
    public final P at(int x, int y) {
        return x(x).y(y);
    }

    /** Width in pixels. */
    public final P width(int width) {
        return put("width", width);
    }

    /** Width: pixels plus a fraction of the parent's width. */
    public final P width(int width, double parentPercent) {
        put("width", width);
        return put("width_pc", parentPercent);
    }

    /** Height in pixels. */
    public final P height(int height) {
        return put("height", height);
    }

    /** Height: pixels plus a fraction of the parent's height. */
    public final P height(int height, double parentPercent) {
        put("height", height);
        return put("height_pc", parentPercent);
    }

    /** Shorthand for {@code width(width).height(height)}. */
    public final P size(int width, int height) {
        return width(width).height(height);
    }

    /** Colour as {@code 0xRRGGBBAA}; the full 32 bits are sent unsigned. */
    public final P rgba(int rgba) {
        return put("rgba", Integer.toUnsignedLong(rgba));
    }

    /** Whether clicks pass through this component to what is underneath. */
    public final P clickthrough(boolean isClickthrough) {
        return put("clickthrough", isClickthrough);
    }

    /** Whether nothing has been set. */
    public final boolean isEmpty() {
        return values.isEmpty();
    }

    /** The props as the wire carries them, in the order they were set. A copy. */
    public final Map<String, Object> wireValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    final P putText(String text) {
        return put("text", Objects.requireNonNull(text, "text"));
    }
}
