package com.botwithus.bot.api.model;

import java.util.List;
import java.util.Optional;

/**
 * A batch projection ({@code world_to_screen}): one {@link ScreenProjection} per tile asked
 * for, in the order asked, and the scene viewport in the same pixels.
 *
 * @param viewport the scene viewport, empty when the agent had none to report
 * @param points   one projection per requested tile, in request order
 */
public record WorldToScreen(Optional<Viewport> viewport, List<ScreenProjection> points) {

    /** Most tiles one call may project; the agent refuses more. */
    public static final int MAX_POINTS = 64;

    public WorldToScreen {
        if (viewport == null) {
            throw new IllegalArgumentException("viewport");
        }
        points = List.copyOf(points);
    }

    /**
     * The scene viewport in window client pixels.
     *
     * @param x      left
     * @param y      top
     * @param width  width
     * @param height height
     */
    public record Viewport(int x, int y, int width, int height) {

        /** The viewport's centre x. */
        public int centreX() {
            return x + width / 2;
        }

        /** The viewport's centre y. */
        public int centreY() {
            return y + height / 2;
        }
    }
}
