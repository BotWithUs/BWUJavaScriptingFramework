package com.botwithus.bot.core.worldwalker;

/**
 * World tile coordinate. Mirrors the C ABI {@code WwTile} (12 bytes).
 */
public record WwTile(int x, int y, int plane) {
}
