package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A slider over an integer range. The user moving it raises
 * {@link UiHook#ON_SELECTION_CHANGED}; a script's own {@code value} set does not.
 */
public final class Slider extends InteractiveComponent {

    Slider(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(SliderProps props) {
        node().set(props);
    }

    /** The slider's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.SliderState state() {
        return switch (node().widget()) {
            case UiWidgetState.SliderState s -> s;
            default -> throw new IllegalStateException("not SliderState keys: " + this);
        };
    }

    /** The position, read back through the client. */
    public int value() {
        return state().value();
    }

    /** Listens for {@link UiHook#ON_SELECTION_CHANGED}, decoded as a slider's. */
    public UiSubscription onValueChanged(Consumer<? super UiHookEvent.SliderChanged> listener) {
        requireListener(listener);
        return subscribe(UiHook.ON_SELECTION_CHANGED, event -> {
            switch (event) {
                case UiHookEvent.SliderChanged changed -> listener.accept(changed);
                default -> {
                }
            }
        });
    }
}
