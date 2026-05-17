package com.botwithus.bot.api.event;

/**
 * Common interface for every typed game event distributed through the {@link EventBus}.
 *
 * <p>The hierarchy is closed: each variant is a {@code record} permitted below.
 * Consumers can dispatch with an exhaustive switch over {@code GameEvent}, and
 * the compiler will refuse to build when a new variant is added without coverage.</p>
 *
 * <p>Every event carries a millisecond {@link #timestamp()}; {@link #type()} returns
 * the event's simple class name, which is the stable, scriptable discriminator.</p>
 *
 * @see EventBus
 */
public sealed interface GameEvent
        permits ActionExecutedEvent,
                BreakEndedEvent,
                BreakStartedEvent,
                ChatMessageEvent,
                KeyInputEvent,
                LoginStateChangeEvent,
                TickEvent,
                VarChangeEvent,
                VarbitChangeEvent,
                WalkArrivedEvent,
                WalkCancelledEvent,
                WalkFailedEvent {

    /** Timestamp at which the event was constructed, in milliseconds since epoch. */
    long timestamp();

    /** Returns the event's simple class name as a stable string discriminator. */
    default String type() {
        return getClass().getSimpleName();
    }
}
