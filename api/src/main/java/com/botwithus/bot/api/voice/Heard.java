package com.botwithus.bot.api.voice;

import java.util.Objects;

/**
 * One phrase the recogniser heard.
 *
 * @param phrase     exactly one of the strings passed to {@link VoiceInput#listen}, the same
 *                   instance the script supplied, so it can be compared with {@code equals}
 *                   or used as a map key
 * @param confidence the engine's confidence in the match. The built-in Windows engine
 *                   reports roughly {@code 0..1}. A command-and-control grammar always
 *                   matches <em>something</em>, so background speech can come through as a
 *                   low-confidence hit; scripts that act on destructive commands should
 *                   ignore hits below a threshold of their choosing
 */
public record Heard(String phrase, float confidence) {

    public Heard {
        Objects.requireNonNull(phrase, "phrase");
    }
}
