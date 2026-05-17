package com.botwithus.bot.api.event;

import com.botwithus.bot.api.model.ChatMessage;

/**
 * Event fired when a chat message is received in the game.
 *
 * <p>Subscribe to this event via the {@link EventBus} to react to chat messages:</p>
 * <pre>{@code
 * eventBus.subscribe(ChatMessageEvent.class, event -> {
 *     ChatMessage msg = event.message();
 *     System.out.println(msg.playerName() + ": " + msg.text());
 * });
 * }</pre>
 *
 * @param message   the chat message payload
 * @param timestamp event creation time in milliseconds since epoch
 *
 * @see EventBus
 * @see ChatMessage
 */
public record ChatMessageEvent(ChatMessage message, long timestamp) implements GameEvent {

    public ChatMessageEvent(ChatMessage message) {
        this(message, System.currentTimeMillis());
    }
}
