package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;

/**
 * A script from the picker's local catalogue, with the name the runtime knows it by.
 *
 * @param key the {@link ScriptEntry#key()} of its picker row
 */
record LocalScript(int key, BotScript script, String name) {
}
