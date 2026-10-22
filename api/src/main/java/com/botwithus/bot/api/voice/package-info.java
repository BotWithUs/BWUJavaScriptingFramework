/**
 * Voice commands for script UIs: offline, grammar-constrained speech recognition.
 *
 * <p>{@link com.botwithus.bot.api.voice.VoiceInput} is the entry point, reached through
 * {@link com.botwithus.bot.api.ScriptContext#getVoiceInput()}. A script lists the phrases
 * it understands, gets a {@link com.botwithus.bot.api.voice.VoiceSession}, and receives a
 * {@link com.botwithus.bot.api.voice.Heard} for each one spoken. Turning phrases into
 * actions is left to the script.</p>
 */
package com.botwithus.bot.api.voice;
