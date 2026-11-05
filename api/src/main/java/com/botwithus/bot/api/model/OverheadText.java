package com.botwithus.bot.api.model;

/**
 * Text showing above an entity, from {@link com.botwithus.bot.api.GameAPI#getOverheadText}.
 *
 * @param text        the text, UTF-8 decoded, cut on a character boundary after 255 bytes (the read
 *                    then reports {@code isTruncated}). Bytes that are not valid UTF-8 end it early
 *                    without reporting that
 * @param expiresInMs milliseconds it has left at the moment of the read
 */
public record OverheadText(String text, long expiresInMs) {
}
