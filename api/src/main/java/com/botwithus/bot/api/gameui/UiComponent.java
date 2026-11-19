package com.botwithus.bot.api.gameui;

/**
 * A component the script created. Each type has its own class so that only the
 * properties and calls that apply to it can be spelled.
 *
 * <p>Every call is one round-trip. Once the component is gone (destroyed, or
 * announced {@link UiEvent#HOOK_DESTROYED}) every call fails with the agent's
 * {@code unknown handle} refusal.</p>
 *
 * <p>Not sealed: the agent will learn more types (buttons, input fields, sliders and
 * so on), and each arrives as a new implementation. Do not switch exhaustively over
 * component types. Only this package's classes are handed to scripts; an
 * implementation a script writes itself is just a handle number and gains nothing.</p>
 */
public interface UiComponent extends UiParent {

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
