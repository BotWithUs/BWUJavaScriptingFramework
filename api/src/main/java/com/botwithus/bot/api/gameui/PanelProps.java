package com.botwithus.bot.api.gameui;

/**
 * Properties of a {@link Panel}. There is no spacing: the agent refuses it as not
 * supported yet.
 */
public final class PanelProps extends UiProps<PanelProps> {

    public PanelProps() {
    }

    @Override
    PanelProps self() {
        return this;
    }

    /** Whether children are laid out top to bottom rather than left to right. */
    public PanelProps vertical(boolean isVertical) {
        return put("vertical", isVertical);
    }
}
