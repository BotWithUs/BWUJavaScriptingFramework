package com.botwithus.bot.api.model;

/**
 * Text showing above an entity, from {@link com.botwithus.bot.api.GameAPI#getOverheadText}.
 *
 * @param text        the text, UTF-8 decoded; kept whole up to 255 bytes and cut on a character
 *                    boundary after that
 * @param expiresInMs milliseconds it has left at the moment of the read
 */
public record OverheadText(String text, int expiresInMs) {
}
