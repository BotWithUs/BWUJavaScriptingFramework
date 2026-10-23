package com.botwithus.bot.cli.gui.window;

/**
 * A move by the top bar in progress: where on the window the cursor holds it.
 *
 * <p>The window follows the cursor in screen coordinates, not by the mouse
 * delta ImGui reports: that delta is relative to the window, which is itself
 * moving, so it would feed back on itself.</p>
 *
 * @param offsetX the cursor's distance from the window's left edge
 * @param offsetY the cursor's distance from the window's top edge
 */
public record MoveDrag(int offsetX, int offsetY) {

    /** Holds {@code window} at the point under {@code cursor}. */
    public static MoveDrag grab(WindowRect window, ScreenPoint cursor) {
        return new MoveDrag(cursor.x() - window.x(), cursor.y() - window.y());
    }

    /**
     * Holds a maximised window that is being dragged out of maximised, as
     * Windows does: the restored window keeps the cursor the same fraction of
     * the way across its top bar, and at the same depth into it, so the bar
     * stays under the cursor although the window shrinks.
     *
     * @param maximised the bounds while maximised, where the drag began
     * @param normal    the bounds it restores to; only the size matters
     */
    public static MoveDrag grabRestoring(WindowRect maximised, WindowRect normal, ScreenPoint cursor) {
        double across = (cursor.x() - maximised.x()) / (double) maximised.width();
        int offsetX = (int) Math.round(across * normal.width());
        int offsetY = Math.clamp(cursor.y() - maximised.y(), 0, normal.height() - 1);
        return new MoveDrag(offsetX, offsetY);
    }

    /** {@code window} moved so the held point is under {@code cursor}. */
    public WindowRect positionAt(WindowRect window, ScreenPoint cursor) {
        return window.movedTo(cursor.x() - offsetX, cursor.y() - offsetY);
    }
}
