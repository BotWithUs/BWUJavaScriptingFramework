package com.botwithus.bot.api.gameui;

/**
 * A text label.
 */
public final class Text implements UiComponent {

    private final UiNode node;

    Text(UiNode node) {
        this.node = node;
    }

    /** Applies {@code props}, all or nothing. */
    public void set(TextProps props) {
        node.set(props);
    }

    /** The text shown, read back through the client ({@code ui.get} with {@code keys: ["content"]}). */
    public String content() {
        return node.api().uiGet(node.handle(), true).content().orElse("");
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
