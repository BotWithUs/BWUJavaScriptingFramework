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
     * destroyed or the script stops, closing one sends nothing, and no listener runs
     * after the script's release has begun, even for an event already queued.</p>
     *
     * <p><b>An event can be lost to a {@code destroyed}.</b> Events are queued and
     * their listeners looked up when they run. If the component's {@code destroyed}
     * announcement is handled first, an event the client raised just before it is
     * dropped: there is no longer a component to deliver it for.</p>
     *
     * <p>Only the script that created the component can subscribe to it; host code
     * cannot subscribe to a script's component either, since a listener runs as the
     * component's owner.</p>
     *
     * <p>The default implementation, for a {@code UiComponent} this package did not
     * make, refuses: such an object is only a handle number with no connection.</p>
     *
     * @throws UiRefusedException when the agent refuses the hook, for example
     *                            {@code ui: hook not valid for this type}
     */
    default UiSubscription subscribe(UiHook hook, Consumer<? super UiHookEvent> listener) {
        throw new UnsupportedOperationException("not a component made by GameUi: " + this);
    }

    /**
     * Stops every listener the script has for {@code hook} on this component and
     * unsubscribes. Returns whether the agent had the hook subscribed; {@code false},
     * not a refusal, when the component is already gone. Refuses, by default, as
     * {@link #subscribe} does.
     */
    default boolean unsubscribe(UiHook hook) {
        throw new UnsupportedOperationException("not a component made by GameUi: " + this);
    }

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
