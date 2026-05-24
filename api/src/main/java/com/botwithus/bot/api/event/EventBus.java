package com.botwithus.bot.api.event;

import java.util.function.Consumer;

/**
 * Publish-subscribe event bus for game events.
 *
 * <p>Scripts can subscribe to specific event types and receive callbacks when those
 * events are published. All listeners are typed to a specific {@link GameEvent} subclass.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * eventBus.subscribe(ChatMessageEvent.class, event -> {
 *     log.info("Chat: {}", event.message().text());
 * });
 * }</pre>
 *
 * @see GameEvent
 * @see ChatMessageEvent
 */
public interface EventBus {

    /**
     * Subscribes a listener to receive events of the specified type.
     *
     * @param <T>       the event type
     * @param eventType the event class to listen for
     * @param listener  the callback to invoke when an event of this type is published
     */
    <T extends GameEvent> void subscribe(Class<T> eventType, Consumer<T> listener);

    /**
     * Removes a previously registered listener for the specified event type.
     *
     * @param <T>       the event type
     * @param eventType the event class the listener was registered for
     * @param listener  the listener to remove
     */
    <T extends GameEvent> void unsubscribe(Class<T> eventType, Consumer<T> listener);

    /**
     * Publishes an event to all registered listeners of its type.
     *
     * @param event the event to publish
     */
    void publish(GameEvent event);
}
