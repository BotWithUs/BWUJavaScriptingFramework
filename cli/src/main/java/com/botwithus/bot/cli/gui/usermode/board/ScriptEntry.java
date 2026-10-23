package com.botwithus.bot.cli.gui.usermode.board;

/**
 * One row in the Start Script picker.
 *
 * @param key  opaque handle the board uses to find the script again when started
 * @param info what the row and details pane show
 */
public record ScriptEntry(int key, ScriptInfo info) {
}
