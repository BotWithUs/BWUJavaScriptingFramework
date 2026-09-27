package com.botwithus.bot.cli.gui.window;

import java.util.Optional;

/**
 * Which resize edge, if any, a point near the window's border grabs.
 *
 * <p>The frameless window has no native border to resize from, so the host draws
 * its own: a band {@code border} pixels deep along each side. Where two sides
 * meet, the band's first {@code corner} pixels along either side grab the corner,
 * which makes the corners easier to hit than the band is deep, as on a native
 * frame.</p>
 *
 * @param border how far in from each side the band reaches, in pixels
 * @param corner how far along a side the corner's grab reaches, in pixels
 */
public record BorderHitTest(float border, float corner) {

    /**
     * The edge a point grabs.
     *
     * @param x      window-relative, 0 at the left edge
     * @param y      window-relative, 0 at the top edge
     * @param width  the window's width
     * @param height the window's height
     * @return the edge, or empty when the point is inside the band or outside the window
     */
    public Optional<ResizeEdge> edgeAt(float x, float y, float width, float height) {
        if (x < 0f || y < 0f || x >= width || y >= height) {
            return Optional.empty();
        }
        boolean isNearTop = y < border;
        boolean isNearBottom = y >= height - border;
        boolean isNearLeft = x < border;
        boolean isNearRight = x >= width - border;
        if (!(isNearTop || isNearBottom || isNearLeft || isNearRight)) {
            return Optional.empty();
        }
        // Along the top or bottom band, the corner run widens which side counts;
        // along the left or right band, it widens which end counts.
        boolean isLeft = isNearLeft || ((isNearTop || isNearBottom) && x < corner);
        boolean isRight = isNearRight || ((isNearTop || isNearBottom) && x >= width - corner);
        boolean isTop = isNearTop || ((isNearLeft || isNearRight) && y < corner);
        boolean isBottom = isNearBottom || ((isNearLeft || isNearRight) && y >= height - corner);
        return Optional.of(edgeFor(nearer(isTop, isBottom, y, height), nearer(isLeft, isRight, x, width)));
    }

    /** Which of two opposite sides, when a long corner run makes both claim the point. */
    private static Side nearer(boolean isLow, boolean isHigh, float at, float extent) {
        if (isLow && isHigh) {
            return at < extent - at ? Side.LOW : Side.HIGH;
        }
        if (isLow) {
            return Side.LOW;
        }
        return isHigh ? Side.HIGH : Side.NONE;
    }

    private static ResizeEdge edgeFor(Side vertical, Side horizontal) {
        return switch (vertical) {
            case LOW -> switch (horizontal) {
                case LOW -> ResizeEdge.NORTH_WEST;
                case HIGH -> ResizeEdge.NORTH_EAST;
                case NONE -> ResizeEdge.NORTH;
            };
            case HIGH -> switch (horizontal) {
                case LOW -> ResizeEdge.SOUTH_WEST;
                case HIGH -> ResizeEdge.SOUTH_EAST;
                case NONE -> ResizeEdge.SOUTH;
            };
            case NONE -> horizontal == Side.LOW ? ResizeEdge.WEST : ResizeEdge.EAST;
        };
    }

    /** One axis: the low side (top or left), the high side (bottom or right), or neither. */
    private enum Side { LOW, HIGH, NONE }
}
