package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.core.window.ClientArea;

/**
 * Where a window pinned to a game sits: its top-left corner's offset from the
 * top-left of the game's client area, so it moves with the game.
 *
 * @param x pixels right of the client area's left edge
 * @param y pixels below the client area's top edge
 */
record GameOffset(float x, float y) {

    /** The default place: the client area's top-right corner, {@code margin} in from both edges. */
    static GameOffset topRight(ClientArea area, float width, float height, float margin) {
        return new GameOffset(area.width() - width - margin, margin).clampedTo(area, width, height);
    }

    /** The offset of a window whose top-left is at {@code (screenX, screenY)} on the desktop. */
    static GameOffset at(ClientArea area, float screenX, float screenY) {
        return new GameOffset(screenX - area.x(), screenY - area.y());
    }

    /**
     * This offset moved the least to keep a {@code width} by {@code height}
     * window inside the client area. A window larger than the area keeps its
     * top-left corner on the area's.
     */
    GameOffset clampedTo(ClientArea area, float width, float height) {
        return new GameOffset(clamp(x, area.width() - width), clamp(y, area.height() - height));
    }

    float screenX(ClientArea area) {
        return area.x() + x;
    }

    float screenY(ClientArea area) {
        return area.y() + y;
    }

    private static float clamp(float value, float max) {
        return Math.max(0f, Math.min(value, max));
    }
}
