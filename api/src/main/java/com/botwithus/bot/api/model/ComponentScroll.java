package com.botwithus.bot.api.model;

/**
 * Scroll state of a layer component (component map keys {@code scrollable}, {@code scroll_x},
 * {@code scroll_y}, {@code scroll_w}, {@code scroll_h}). Only layers scroll; every other category
 * reports {@link #NONE}.
 *
 * @param isScrollable true only for layers
 * @param x            horizontal scroll offset, clamped as the client clamps it:
 *                     {@code 0 .. max(contentWidth, width) - width}
 * @param y            vertical scroll offset, clamped the same way against the height
 * @param contentWidth width of the scrolled content
 * @param contentHeight height of the scrolled content
 */
public record ComponentScroll(boolean isScrollable, int x, int y, int contentWidth, int contentHeight) {

    /** What a non-layer, or a producer that predates the scroll keys, reports. */
    public static final ComponentScroll NONE = new ComponentScroll(false, 0, 0, 0, 0);
}
