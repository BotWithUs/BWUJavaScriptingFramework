package com.botwithus.bot.cli.gui.window;

/**
 * A resize in progress: the window's bounds when it began, the edge being
 * dragged and where the cursor grabbed it.
 *
 * <p>Every frame's bounds are computed from the start, never from the previous
 * frame, so a drag that pauses at the minimum size and comes back resumes
 * exactly where the cursor is rather than drifting.</p>
 */
public record ResizeDrag(WindowRect start, ResizeEdge edge, ScreenPoint grab) {

    /**
     * The window's bounds with the grabbed edge following the cursor. The
     * opposite edge stays put, and neither side shrinks below its minimum.
     */
    public WindowRect rectAt(ScreenPoint cursor, int minWidth, int minHeight) {
        int dx = cursor.x() - grab.x();
        int dy = cursor.y() - grab.y();
        int left = start.x();
        int top = start.y();
        int width = start.width();
        int height = start.height();
        if (edge.isLeft()) {
            left = Math.min(start.x() + dx, start.right() - minWidth);
            width = start.right() - left;
        } else if (edge.isRight()) {
            width = Math.max(minWidth, start.width() + dx);
        }
        if (edge.isTop()) {
            top = Math.min(start.y() + dy, start.bottom() - minHeight);
            height = start.bottom() - top;
        } else if (edge.isBottom()) {
            height = Math.max(minHeight, start.height() + dy);
        }
        return new WindowRect(left, top, width, height);
    }
}
