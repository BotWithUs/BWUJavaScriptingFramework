package com.botwithus.bot.core.voice;

/**
 * One raw recognition from the engine, before it is matched to a phrase.
 *
 * @param ruleId     the id of the top-level rule that matched
 * @param confidence the engine's own confidence score for the match
 */
record Recognition(long ruleId, float confidence) {
}
