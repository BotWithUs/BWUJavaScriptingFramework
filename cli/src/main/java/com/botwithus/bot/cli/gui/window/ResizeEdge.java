package com.botwithus.bot.cli.gui.window;

import imgui.flag.ImGuiMouseCursor;

/** Which side or corner of the window a resize drag moves. */
public enum ResizeEdge {
    NORTH(true, false, false, false),
    SOUTH(false, true, false, false),
    WEST(false, false, true, false),
    EAST(false, false, false, true),
    NORTH_WEST(true, false, true, false),
    NORTH_EAST(true, false, false, true),
    SOUTH_WEST(false, true, true, false),
    SOUTH_EAST(false, true, false, true);

    private final boolean isTop;
    private final boolean isBottom;
    private final boolean isLeft;
    private final boolean isRight;

    ResizeEdge(boolean isTop, boolean isBottom, boolean isLeft, boolean isRight) {
        this.isTop = isTop;
        this.isBottom = isBottom;
        this.isLeft = isLeft;
        this.isRight = isRight;
    }

    /** Moves the top edge. */
    public boolean isTop() {
        return isTop;
    }

    /** Moves the bottom edge. */
    public boolean isBottom() {
        return isBottom;
    }

    /** Moves the left edge. */
    public boolean isLeft() {
        return isLeft;
    }

    /** Moves the right edge. */
    public boolean isRight() {
        return isRight;
    }

    /** The resize cursor that shows this edge, as an {@code ImGuiMouseCursor}. */
    public int mouseCursor() {
        return switch (this) {
            case NORTH, SOUTH -> ImGuiMouseCursor.ResizeNS;
            case WEST, EAST -> ImGuiMouseCursor.ResizeEW;
            case NORTH_WEST, SOUTH_EAST -> ImGuiMouseCursor.ResizeNWSE;
            case NORTH_EAST, SOUTH_WEST -> ImGuiMouseCursor.ResizeNESW;
        };
    }
}
