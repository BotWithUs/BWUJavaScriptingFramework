package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * What every interactive component (button, check, list, input field, slider, combo
 * field, panel, grid) shares: the handle, the verbs and whether it takes input.
 * Package-private, so the only subclasses are this package's, and none adds a field.
 */
abstract sealed class InteractiveComponent implements UiComponent
        permits Button, Check, ComboField, Grid, InputField, ListBox, Panel, Slider {

    private final UiNode node;

    InteractiveComponent(UiNode node) {
        this.node = node;
    }

    final UiNode node() {
        return node;
    }

    /** Whether the component takes input, read back through the client. */
    public final boolean isEnabled() {
        return node.readback().isEnabled();
    }

    @Override
    public final long handle() {
        return node.handle();
    }

    @Override
    public final UiHandleInfo info() {
        return node.info();
    }

    @Override
    public final UiReadback readback() {
        return node.readback();
    }

    @Override
    public final UiSubscription subscribe(UiHook hook, Consumer<? super UiHookEvent> listener) {
        return node.subscribe(hook, listener);
    }

    @Override
    public final boolean unsubscribe(UiHook hook) {
        return node.unsubscribe(hook);
    }

    @Override
    public final void toFront() {
        node.order(UiOrder.FRONT);
    }

    @Override
    public final void toBack() {
        node.order(UiOrder.BACK);
    }

    @Override
    public final boolean destroy() {
        return node.destroy();
    }

    @Override
    public final String toString() {
        return node.toString();
    }

    /** A listener for {@code hook} that passes on only {@link UiHookEvent.ButtonPressed}. */
    final UiSubscription onButtonPressed(Consumer<? super UiHookEvent.ButtonPressed> listener) {
        requireListener(listener);
        return subscribe(UiHook.ON_BUTTON_PRESSED, event -> {
            switch (event) {
                case UiHookEvent.ButtonPressed pressed -> listener.accept(pressed);
                default -> {
                }
            }
        });
    }

    /** A listener that passes on only {@link UiHookEvent.SelectionChanged}. */
    final UiSubscription onEntrySelection(Consumer<? super UiHookEvent.SelectionChanged> listener) {
        requireListener(listener);
        return subscribe(UiHook.ON_SELECTION_CHANGED, event -> {
            switch (event) {
                case UiHookEvent.SelectionChanged changed -> listener.accept(changed);
                default -> {
                }
            }
        });
    }

    static void requireListener(Object listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener");
        }
    }
}
