package com.botwithus.bot.api.gameui;

import java.util.List;

/**
 * A canvas: a box the script draws lines, rectangles, gradients and triangles on.
 *
 * <p>Draw through {@link #draw()}, which collects ops and sends them in as few
 * calls as the agent allows. A canvas holds at most {@link UiLimits#MAX_CANVAS_ELEMENTS}
 * elements.</p>
 */
public final class Canvas implements UiComponent {

    private final UiNode node;

    Canvas(UiNode node) {
        this.node = node;
    }

    /** Applies {@code props}, all or nothing. */
    public void set(CanvasProps props) {
        node.set(props);
    }

    /** Starts a batch of drawing ops; nothing is sent until {@link CanvasBatch#submit()}. */
    public CanvasBatch draw() {
        return new CanvasBatch(node);
    }

    /** Runs {@code ops} now, split as {@link CanvasBatch#submit()} splits, and returns the element count. */
    public int apply(List<CanvasOp> ops) {
        return draw().addAll(ops).submit();
    }

    /** Removes every element. Returns the element count afterwards, {@code 0}. */
    public int clear() {
        return draw().clear().submit();
    }

    @Override
    public long handle() {
        return node.handle();
    }

    @Override
    public UiHandleInfo info() {
        return node.info();
    }

    @Override
    public UiReadback readback() {
        return node.readback();
    }

    @Override
    public void toFront() {
        node.order(UiOrder.FRONT);
    }

    @Override
    public void toBack() {
        node.order(UiOrder.BACK);
    }

    @Override
    public boolean destroy() {
        return node.destroy();
    }

    @Override
    public String toString() {
        return node.toString();
    }
}
