package com.botwithus.bot.api.gameui;

import java.util.Optional;

/**
 * A component read back through the client's own getters ({@code ui.get}).
 *
 * <p>Geometry is post-layout and relative to the parent. A field that does not
 * apply to the component's type reads {@link #NOT_APPLICABLE}: {@code numElements}
 * outside a canvas, {@code lineWidth}/{@code lineInvert} outside a line,
 * {@code fill}/{@code outlineThickness} outside a rectangle.</p>
 *
 * @param info             the handle this was read from
 * @param x                post-layout x, parent relative
 * @param y                post-layout y, parent relative
 * @param width            post-layout width
 * @param height           post-layout height
 * @param rgba             colour as {@code 0xRRGGBBAA}
 * @param isVisible        global visibility, computed by the agent's port of the
 *                         client's own visibility check
 * @param isRestricted     the client flags the component restricted (password fields)
 * @param isClickthrough   clicks pass through to what is underneath
 * @param numElements      canvas element count
 * @param lineWidth        line width
 * @param lineInvert       {@code 1} when the line is inverted, {@code 0} when not
 * @param fill             {@code 1} when the rectangle is filled, {@code 0} when not
 * @param outlineThickness rectangle outline thickness
 * @param useCount         the component's shared-pointer use count; diagnostic only
 * @param isOpFlagsRead    whether the client's op flags could be read
 * @param opMask           the client's op mask; {@code 0} on an agent component
 * @param opTarget         the client's op target; {@code -1} on an agent component
 * @param content          a text or input field's text, present only when it was asked for
 * @param isEnabled        whether the component is enabled; {@code true} from an agent
 *                         that predates interactive components and does not report it
 * @param widget           the interactive type's own keys; empty for a grid and for
 *                         every non-interactive type
 */
public record UiReadback(UiHandleInfo info, int x, int y, int width, int height, int rgba,
                         boolean isVisible, boolean isRestricted, boolean isClickthrough,
                         int numElements, int lineWidth, int lineInvert, int fill,
                         int outlineThickness, int useCount, boolean isOpFlagsRead,
                         long opMask, int opTarget, Optional<String> content,
                         boolean isEnabled, Optional<UiWidgetState> widget) {

    /** What a field that does not apply to the component's type reads. */
    public static final int NOT_APPLICABLE = -1;

    public UiReadback {
        if (info == null) {
            throw new IllegalArgumentException("info");
        }
        content = content != null ? content : Optional.empty();
        widget = widget != null ? widget : Optional.empty();
    }

    /** The non-interactive readback: enabled, with no widget keys. */
    public UiReadback(UiHandleInfo info, int x, int y, int width, int height, int rgba,
                      boolean isVisible, boolean isRestricted, boolean isClickthrough,
                      int numElements, int lineWidth, int lineInvert, int fill,
                      int outlineThickness, int useCount, boolean isOpFlagsRead,
                      long opMask, int opTarget, Optional<String> content) {
        this(info, x, y, width, height, rgba, isVisible, isRestricted, isClickthrough, numElements,
                lineWidth, lineInvert, fill, outlineThickness, useCount, isOpFlagsRead, opMask, opTarget,
                content, true, Optional.empty());
    }

    /** The same readback, about {@code other} instead. */
    public UiReadback withInfo(UiHandleInfo other) {
        return new UiReadback(other, x, y, width, height, rgba, isVisible, isRestricted, isClickthrough,
                numElements, lineWidth, lineInvert, fill, outlineThickness, useCount, isOpFlagsRead, opMask,
                opTarget, content, isEnabled, widget);
    }
}
