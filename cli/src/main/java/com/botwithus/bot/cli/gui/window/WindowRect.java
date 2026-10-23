package com.botwithus.bot.cli.gui.window;

/**
 * A rectangle in screen pixels: a window's outer bounds or a monitor's work
 * area. {@code x}/{@code y} is the top-left corner and may be negative on a
 * monitor left of or above the primary one.
 */
public record WindowRect(int x, int y, int width, int height) {

    /** One past the right-most column. */
    public int right() {
        return x + width;
    }

    /** One past the bottom-most row. */
    public int bottom() {
        return y + height;
    }

    /** Pixels this rectangle shares with {@code other}; 0 when they do not touch. */
    public long overlapArea(WindowRect other) {
        long w = Math.max(0, Math.min(right(), other.right()) - Math.max(x, other.x));
        long h = Math.max(0, Math.min(bottom(), other.bottom()) - Math.max(y, other.y));
        return w * h;
    }

    /** The same size with its top-left at ({@code newX}, {@code newY}). */
    public WindowRect movedTo(int newX, int newY) {
        return new WindowRect(newX, newY, width, height);
    }

    /** This size, centred in {@code area}. */
    public WindowRect centredIn(WindowRect area) {
        return movedTo(area.x + (area.width - width) / 2, area.y + (area.height - height) / 2);
    }

    /**
     * This rectangle made to lie wholly inside {@code area}: shrunk to fit if it
     * is larger, then moved the shortest distance that brings it inside.
     */
    public WindowRect fittedInto(WindowRect area) {
        int w = Math.min(width, area.width);
        int h = Math.min(height, area.height);
        int nx = Math.clamp(x, area.x, area.right() - w);
        int ny = Math.clamp(y, area.y, area.bottom() - h);
        return new WindowRect(nx, ny, w, h);
    }
}
