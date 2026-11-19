package com.botwithus.bot.api.gameui;

/**
 * A component the script created. Each type has its own class so that only the
 * properties and calls that apply to it can be spelled.
 *
 * <p>Every call is one round-trip. Once the component is gone (destroyed, or
 * announced {@link UiEvent#HOOK_DESTROYED}) every call fails with the agent's
 * {@code unknown handle} refusal.</p>
 */
public sealed interface UiComponent extends UiParent permits Canvas, Layer, Line, Rectangle, Text {

    /** Moves the component in front of its siblings. */
    void toFront();

    /** Moves the component behind its siblings. */
    void toBack();

    /**
     * Destroys the component. Returns whether its last strong reference is gone,
     * as the agent reads it; {@code false} is diagnostic, not a failure.
     */
    boolean destroy();
}
