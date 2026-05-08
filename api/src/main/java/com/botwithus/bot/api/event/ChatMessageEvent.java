package com.botwithus.bot.api.event;

import com.botwithus.bot.api.model.ChatMessage;

/**
 * Event fired when a chat message is received in the game.
 *
 * <p>Subscribe to this event via the {@link EventBus} to react to chat messages:</p>
 * <pre>{@code
 * eventBus.subscribe(ChatMessageEvent.class, event -> {
 *     ChatMessage msg = event.getMessage();
 *     log.info("{}: {}", msg.playerName(), msg.text());
 * });
 * }</pre>
 *
 * @see EventBus
 * @see ChatMessage
 */
public class ChatMessageEvent extends GameEvent {
    private final ChatMessage message;

    /**
     * Creates a new chat message event.
     *
     * @param message the chat message payload
     */
    public ChatMessageEvent(ChatMessage message) {
        super("chat_message");
        this.message = message;
    }

    /**
     * Returns the chat message associated with this event.
     *
     * @return the chat message
     */
    public ChatMessage getMessage() { return message; }
}
