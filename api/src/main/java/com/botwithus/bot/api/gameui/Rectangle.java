package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A rectangle, filled or outlined.
 */
public final class Rectangle implements UiComponent {

    private final UiNode node;

    Rectangle(UiNode node) {
        this.node = node;
    }

    /** Applies {@code props}, all or nothing. */
    public void set(RectangleProps props) {
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
    public UiSubscription subscribe(UiHook hook, Consumer<? super UiHookEvent> listener) {
        return node.subscribe(hook, listener);
    }

    @Override
    public boolean unsubscribe(UiHook hook) {
        return node.unsubscribe(hook);
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
