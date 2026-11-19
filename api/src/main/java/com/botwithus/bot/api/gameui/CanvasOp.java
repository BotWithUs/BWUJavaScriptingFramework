package com.botwithus.bot.api.gameui;

/**
 * One drawing operation on a {@link Canvas}, as {@code ui.canvas} takes it.
 *
 * <p>Every argument is an int; colours are {@code 0xRRGGBBAA} and travel as the
 * full unsigned 32 bits. Sealed so the encoder dispatches exhaustively: an op the
 * agent learns later (it refuses {@code text} and {@code sprite} today) arrives
 * as a new record here, and the encoder stops compiling until it handles it.</p>
 *
 * <p>Ranges are the agent's: a bad argument refuses the whole call before its first
 * op runs.</p>
 */
public sealed interface CanvasOp {

    /** A line from {@code (x, y)} to {@code (endX, endY)}. */
    record Line(int x, int y, int endX, int endY, int thickness, int rgba) implements CanvasOp {
    }

    /** A rectangle's outline. */
    record Outline(int x, int y, int width, int height, int thickness, int rgba) implements CanvasOp {
    }

    /** A filled rectangle. */
    record Rect(int x, int y, int width, int height, int rgba) implements CanvasOp {
    }

    /** A filled rectangle shaded left to right. */
    record GradientH(int x, int y, int width, int height, int rgbaStart, int rgbaEnd) implements CanvasOp {
    }

    /** A filled rectangle shaded top to bottom. */
    record GradientV(int x, int y, int width, int height, int rgbaStart, int rgbaEnd) implements CanvasOp {
    }

    /** A filled triangle. */
    record Triangle(int x0, int y0, int x1, int y1, int x2, int y2, int rgba) implements CanvasOp {
    }

    /** Removes every element. */
    record Clear() implements CanvasOp {
    }

    /** Removes the most recently added element. */
    record Undo() implements CanvasOp {
    }
}
