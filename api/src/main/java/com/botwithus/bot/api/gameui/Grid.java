package com.botwithus.bot.api.gameui;

/**
 * A grid container. Its layout parameters are not supported yet, so it takes only the
 * properties every component does.
 */
public final class Grid extends InteractiveComponent {

    Grid(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(GridProps props) {
        node().set(props);
    }
}
