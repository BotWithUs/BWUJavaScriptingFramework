package com.botwithus.bot.api.gameui;

/**
 * A straight line across the component's box.
 */
public final class Line implements UiComponent {

    private final UiNode node;

    Line(UiNode node) {
        this.node = node;
    }

    /** Applies {@code props}, all or nothing. */
    public void set(LineProps props) {
        node.set(props);
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
