package com.botwithus.bot.core.window;

/**
 * A window's client area on the desktop, in screen pixels: the part the game
 * draws into, without its title bar and borders.
 *
 * @param x      the left edge
 * @param y      the top edge
 * @param width  the width, never negative
 * @param height the height, never negative
 */
public record ClientArea(int x, int y, int width, int height) {
}
