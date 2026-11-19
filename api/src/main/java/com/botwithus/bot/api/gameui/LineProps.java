package com.botwithus.bot.api.gameui;

/** Properties of a {@link Line}. */
public final class LineProps extends UiProps<LineProps> {

    public LineProps() {
    }

    @Override
    LineProps self() {
        return this;
    }

    /** Line width, {@code 0}..{@link UiLimits#MAX_THICKNESS}. */
    public LineProps lineWidth(int lineWidth) {
        return put("line_width", lineWidth);
    }

    /** Whether the line is drawn inverted, as the client's own line setter takes it. */
    public LineProps invert(boolean isInverted) {
        return put("line_invert", isInverted);
    }
}
