package com.botwithus.bot.api.gameui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Drawing ops collected for one {@link Canvas} and sent together.
 *
 * <p>{@link #submit()} sends them in calls of at most {@link UiLimits#MAX_CANVAS_OPS},
 * because the agent refuses a larger call whole. Each call is atomic on the agent
 * (a bad op refuses it before its first op runs), but a split batch is not: when
 * call two is refused, call one has already drawn. The refusal is raised as
 * {@link UiRefusedException} either way; {@link Canvas#readback()} reports the
 * element count that resulted.</p>
 *
 * <p>Mutable and not thread-safe. Ops are dropped from the batch once a call has
 * carried them, and the ones not yet sent are kept when a call fails, so a batch
 * can be fixed and retried without redrawing what already drew.</p>
 */
public final class CanvasBatch {

    private final UiNode node;
    private final List<CanvasOp> ops = new ArrayList<>();

    CanvasBatch(UiNode node) {
        this.node = node;
    }

    /** A line from {@code (x, y)} to {@code (endX, endY)}. */
    public CanvasBatch line(int x, int y, int endX, int endY, int thickness, int rgba) {
        return add(new CanvasOp.Line(x, y, endX, endY, thickness, rgba));
    }

    /** A rectangle's outline. */
    public CanvasBatch outline(int x, int y, int width, int height, int thickness, int rgba) {
        return add(new CanvasOp.Outline(x, y, width, height, thickness, rgba));
    }

    /** A filled rectangle. */
    public CanvasBatch rect(int x, int y, int width, int height, int rgba) {
        return add(new CanvasOp.Rect(x, y, width, height, rgba));
    }

    /** A filled rectangle shaded from {@code rgbaStart} on the left to {@code rgbaEnd} on the right. */
    public CanvasBatch gradientH(int x, int y, int width, int height, int rgbaStart, int rgbaEnd) {
        return add(new CanvasOp.GradientH(x, y, width, height, rgbaStart, rgbaEnd));
    }

    /** A filled rectangle shaded from {@code rgbaStart} at the top to {@code rgbaEnd} at the bottom. */
    public CanvasBatch gradientV(int x, int y, int width, int height, int rgbaStart, int rgbaEnd) {
        return add(new CanvasOp.GradientV(x, y, width, height, rgbaStart, rgbaEnd));
    }

    /** A filled triangle. */
    public CanvasBatch triangle(int x0, int y0, int x1, int y1, int x2, int y2, int rgba) {
        return add(new CanvasOp.Triangle(x0, y0, x1, y1, x2, y2, rgba));
    }

    /** Removes every element drawn so far, including earlier ops in this batch. */
    public CanvasBatch clear() {
        return add(new CanvasOp.Clear());
    }

    /** Removes the most recently added element. */
    public CanvasBatch undo() {
        return add(new CanvasOp.Undo());
    }

    /** Appends an op. */
    public CanvasBatch add(CanvasOp op) {
        ops.add(Objects.requireNonNull(op, "op"));
        return this;
    }

    /** Appends every op in {@code more}, in order. */
    public CanvasBatch addAll(List<CanvasOp> more) {
        more.forEach(this::add);
        return this;
    }

    /** How many ops are waiting to be sent. */
    public int size() {
        return ops.size();
    }

    /**
     * Sends the ops and returns the canvas's element count after the last call.
     * An empty batch sends nothing and returns {@link UiReadback#NOT_APPLICABLE}.
     */
    public int submit() {
        int numElements = UiReadback.NOT_APPLICABLE;
        while (!ops.isEmpty()) {
            List<CanvasOp> chunk = ops.subList(0, Math.min(ops.size(), UiLimits.MAX_CANVAS_OPS));
            numElements = node.api().uiCanvas(node.handle(), List.copyOf(chunk));
            chunk.clear();
        }
        return numElements;
    }
}
