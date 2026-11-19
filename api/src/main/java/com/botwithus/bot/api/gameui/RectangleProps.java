package com.botwithus.bot.api.gameui;

/** Properties of a {@link Rectangle}. */
public final class RectangleProps extends UiProps<RectangleProps> {

    public RectangleProps() {
    }

    @Override
    RectangleProps self() {
        return this;
    }

    /** Whether the rectangle is filled. */
    public RectangleProps fill(boolean isFilled) {
        return put("fill", isFilled);
    }

    /** Outline thickness, {@code 0}..{@link UiLimits#MAX_THICKNESS}. */
    public RectangleProps outlineThickness(int thickness) {
        return put("outline_thickness", thickness);
    }
}
