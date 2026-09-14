package com.botwithus.bot.api.model;

/**
 * A message from the game chat history.
 *
 * @param index       the message index in the chat history
 * @param messageType the message type (e.g., public chat, private message, game message)
 * @param text        the message text content
 * @param playerName  the name of the player who sent the message, or {@code null} for system messages
 * @see com.botwithus.bot.api.event.ChatMessageEvent
 */
public record ChatMessage(int index, int messageType, String text, String playerName) {}
