package com.botwithus.bot.api.model;

/**
 * A health bar displayed above an entity's head.
 *
 * @param id    the headbar type ID from cache
 * @param width the current bar width (represents HP percentage, typically 0-255)
 * @see com.botwithus.bot.api.GameAPI#getEntityHeadbars
 */
public record Headbar(int id, int width) {}
