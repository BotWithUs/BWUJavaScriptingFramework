package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A text input field.
 *
 * <p><b>Focus.</b> A user's click focuses the field inside the client, and <b>while it
 * is focused the game's keys go to it</b>: the chatbox and every in-game key handler
 * stop receiving them. {@link #blur()} the field when the script is done with it. The
 * agent never focuses a field itself, and destroying a focused field blurs it first.</p>
 *
 * <p><b>Drawing.</b> On 951-4 an agent field often does not draw at all (see
 * {@link UiWidgetState.InputFieldState#isRendering()}). Content, events and focus
 * work regardless; check {@link #isRendering()} before relying on the user seeing it.</p>
 */
public final class InputField extends InteractiveComponent {

    InputField(UiNode node) {
        super(node);
    }

    /** Applies {@code props}, all or nothing. */
    public void set(InputFieldProps props) {
        node().set(props);
    }

    /** The inputField's own keys, read back through the client ({@code ui.get}). */
    public UiWidgetState.InputFieldState state() {
        return switch (node().widget()) {
            case UiWidgetState.InputFieldState s -> s;
            default -> throw new IllegalStateException("not InputFieldState keys: " + this);
        };
    }

    /** The field's text, read back through the client. Refused for a restricted field. */
    public String content() {
        return node().content();
    }

    /** Whether the client can draw the field. Often {@code false} on 951-4; see the class notes. */
    public boolean isRendering() {
        return state().isRendering();
    }

    /**
     * Takes the client's input focus off the field, through the client's own clear-focus.
     * Returns whether it had focus.
     */
    public boolean blur() {
        return node().blur();
    }

    /**
     * Listens for {@link UiHook#ON_CONTENT_CHANGED}: typed input, submits, focus loss,
     * <b>and the script's own {@code content} sets</b>.
     */
    public UiSubscription onContentChanged(Consumer<? super UiHookEvent.ContentChanged> listener) {
        requireListener(listener);
        return subscribe(UiHook.ON_CONTENT_CHANGED, event -> {
            switch (event) {
                case UiHookEvent.ContentChanged changed -> listener.accept(changed);
                default -> {
                }
            }
        });
    }
}
