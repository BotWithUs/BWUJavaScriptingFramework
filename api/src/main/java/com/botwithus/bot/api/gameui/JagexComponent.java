package com.botwithus.bot.api.gameui;

/**
 * A component the game made, looked up with {@link GameUi#component}. Read-only:
 * usable as a parent and with {@link #readback()}, nothing else. The agent refuses
 * any edit of a Jagex component, so this type offers none.
 *
 * <p>Jagex handles are shared: two scripts looking up the same component get the
 * same handle, and neither can destroy it.</p>
 */
public final class JagexComponent implements UiParent {

    private final UiNode node;

    JagexComponent(UiNode node) {
        this.node = node;
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
    public String toString() {
        return "jagex " + node.info().iface() + ":" + node.info().comp();
    }
}
