/**
 * Typing into the game through component key triggers.
 *
 * <p>{@link com.botwithus.bot.api.input.KeyStroke} is one key event, as a key
 * (type-10) CS2 trigger receives it. {@link com.botwithus.bot.api.GameAPI#fireKeys}
 * sends a sequence of them to any component.
 * {@link com.botwithus.bot.api.input.InputDialog} is the checked path into the
 * game's own input dialog (withdraw-X, add-friend, ...). It validates the value
 * against the dialog's mode before typing it, then submits.</p>
 */
package com.botwithus.bot.api.input;
