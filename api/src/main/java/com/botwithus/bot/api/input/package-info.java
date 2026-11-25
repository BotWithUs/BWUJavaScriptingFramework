/**
 * Typing into the game through component key triggers, and the result types of
 * real key presses.
 *
 * <p>{@link com.botwithus.bot.api.input.KeyStroke} is one key event, as a key
 * (type-10) CS2 trigger receives it. {@link com.botwithus.bot.api.GameAPI#fireKeys}
 * sends a sequence of them to any component.
 * {@link com.botwithus.bot.api.input.InputDialog} is the checked path into the
 * game's own input dialog (withdraw-X, add-friend, ...). It validates the value
 * against the dialog's mode before typing it, then submits.</p>
 *
 * <p>{@link com.botwithus.bot.api.input.KeyResult},
 * {@link com.botwithus.bot.api.input.FocusMode} and
 * {@link com.botwithus.bot.api.input.FocusState} belong to
 * {@link com.botwithus.bot.api.domain.KeyboardAPI}, which posts a real key press
 * to the client (reaching keybinds, which component triggers do not).</p>
 */
package com.botwithus.bot.api.input;
