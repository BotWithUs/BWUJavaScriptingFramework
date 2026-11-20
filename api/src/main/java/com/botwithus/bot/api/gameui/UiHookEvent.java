package com.botwithus.bot.api.gameui;

import java.util.Optional;

/**
 * A client event on one of the script's components, delivered to the listener it
 * subscribed with ({@link UiComponent#subscribe}), already decoded from the
 * agent's {@code args}.
 *
 * <p>Sealed, so a listener can switch over it exhaustively:</p>
 * <pre>{@code
 * slider.subscribe(UiHook.ON_SELECTION_CHANGED, event -> {
 *     switch (event) {
 *         case UiHookEvent.SliderChanged s -> volume = s.value();
 *         default -> { }
 *     }
 * });
 * }</pre>
 *
 * <p>The typed shortcuts ({@link Slider#onValueChanged} and the rest) do that
 * switch for the script. Events run on the script's own event thread, as described
 * at {@link GameUi#onEvent}.</p>
 */
public sealed interface UiHookEvent {

    /** The handle of the component the event is about. */
    long handle();

    /** The hook it was raised for. */
    UiHook hook();

    /**
     * {@link UiHook#ON_CLICK}, {@link UiHook#ON_RELEASE}, {@link UiHook#ON_MOUSE_OVER}
     * or {@link UiHook#ON_MOUSE_LEAVE}: where the pointer was.
     *
     * @param x component relative
     * @param y component relative
     */
    record Pointer(long handle, UiHook hook, int x, int y) implements UiHookEvent {
        public Pointer {
            if (hook == null || !hook.isPointer()) {
                throw new IllegalArgumentException("not a pointer hook: " + hook);
            }
        }
    }

    /**
     * {@link UiHook#ON_BUTTON_PRESSED} on a button or a check.
     *
     * @param current  the state after the press, {@code 0} or {@code 1}; for a check,
     *                 whether it is now checked
     * @param previous the state before it
     */
    record ButtonPressed(long handle, int current, int previous) implements UiHookEvent {
        @Override
        public UiHook hook() {
            return UiHook.ON_BUTTON_PRESSED;
        }

        /** Whether {@link #current()} is on: a toggled button, a checked check. */
        public boolean isOn() {
            return current != 0;
        }
    }

    /**
     * {@link UiHook#ON_CONTENT_CHANGED} on an input field.
     *
     * @param reasonCode the client's reason number; {@link #reason()} names it
     * @param content    the field's text, at most 767 bytes of it; empty when the
     *                   field is restricted, since the agent does not read those
     */
    record ContentChanged(long handle, int reasonCode, Optional<String> content) implements UiHookEvent {
        public ContentChanged {
            content = content != null ? content : Optional.empty();
        }

        @Override
        public UiHook hook() {
            return UiHook.ON_CONTENT_CHANGED;
        }

        /** {@link #reasonCode()} named; {@link ContentChangeReason#UNKNOWN} for a number this host does not know. */
        public ContentChangeReason reason() {
            return ContentChangeReason.fromCode(reasonCode);
        }
    }

    /**
     * {@link UiHook#ON_SELECTION_CHANGED} on a slider.
     *
     * @param value the slider's new value
     */
    record SliderChanged(long handle, int value) implements UiHookEvent {
        @Override
        public UiHook hook() {
            return UiHook.ON_SELECTION_CHANGED;
        }
    }

    /**
     * {@link UiHook#ON_SELECTION_CHANGED} on a combo field or a list.
     *
     * @param entryId   the id of the entry concerned
     * @param eventCode the client's selection-event number; {@link #kind()} names it
     */
    record SelectionChanged(long handle, int entryId, int eventCode) implements UiHookEvent {
        @Override
        public UiHook hook() {
            return UiHook.ON_SELECTION_CHANGED;
        }

        /** {@link #eventCode()} named; {@link SelectionChange#UNKNOWN} for a number this host does not know. */
        public SelectionChange kind() {
            return SelectionChange.fromCode(eventCode);
        }
    }
}
