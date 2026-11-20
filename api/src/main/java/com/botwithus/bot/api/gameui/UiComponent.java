package com.botwithus.bot.api.gameui;

import java.util.function.Consumer;

/**
 * A component the script created. Each type has its own class so that only the
 * properties and calls that apply to it can be spelled.
 *
 * <p>Every call is one round-trip. Once the component is gone (destroyed, or
 * announced {@link UiEvent#HOOK_DESTROYED}) every call fails with the agent's
 * {@code unknown handle} refusal.</p>
 *
 * <p>Not sealed: the agent will learn more types (sprites, models), and each arrives
 * as a new implementation. Do not switch exhaustively over
 * component types. Only this package's classes are handed to scripts; an
 * implementation a script writes itself is just a handle number and gains nothing.</p>
 */
public interface UiComponent extends UiParent {

    /**
     * Listens for {@code hook} on this component. The first listener for a hook makes
     * the agent subscribe ({@code ui.subscribe}); closing the last one unsubscribes.
     * Events arrive decoded ({@link UiHookEvent}) on the script's own event thread,
     * as {@link GameUi#onEvent} describes, and in order.
     *
     * <p>Subscriptions end with the component and with the script: once it is
     * destroyed or the script stops, closing one sends nothing.</p>
     *
     * @throws UiRefusedException when the agent refuses the hook, for example
     *                            {@code ui: hook not valid for this type}
     */
    UiSubscription subscribe(UiHook hook, Consumer<? super UiHookEvent> listener);

    /**
     * Stops every listener the script has for {@code hook} on this component and
     * unsubscribes. Returns whether the agent had the hook subscribed; {@code false},
     * not a refusal, when the component is already gone.
     */
    boolean unsubscribe(UiHook hook);

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
