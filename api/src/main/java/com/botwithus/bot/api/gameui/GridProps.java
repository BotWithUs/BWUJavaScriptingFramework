package com.botwithus.bot.api.gameui;

/**
 * Properties of a {@link Grid}: the ones every component takes. The grid's own
 * layout parameters are refused by the agent as not supported yet.
 */
public final class GridProps extends UiProps<GridProps> {

    public GridProps() {
    }

    @Override
    GridProps self() {
        return this;
    }
}
