package com.botwithus.bot.core.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The phrases a {@code listen} call asked for, validated and turned into the text the
 * speech engine is given.
 *
 * <p>Each phrase becomes its own top-level grammar rule whose id is
 * {@link #ruleIdOf(int) its index plus one}, so a recognition result names the phrase by
 * rule id and maps straight back to the caller's string, never through a text comparison.</p>
 *
 * <p>The engine text keeps letters, digits, apostrophes and hyphens and turns everything
 * else into word breaks. That is the escaping step: SAPI's word format gives {@code /} and
 * {@code ;} a meaning inside a word ({@code /display/lexical/pronunciation;}), so a phrase
 * holding either would otherwise be parsed as something other than what it says.</p>
 */
public final class VoiceGrammar {

    /** More rules than any spoken UI needs; past this the engine slows for no gain. */
    static final int MAX_PHRASES = 1024;
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}'\\-]+");
    private static final String WORD_BREAK = " ";

    private final List<String> phrases;
    private final List<String> engineTexts;

    private VoiceGrammar(List<String> phrases, List<String> engineTexts) {
        this.phrases = phrases;
        this.engineTexts = engineTexts;
    }

    /**
     * Validates {@code phrases}.
     *
     * @throws IllegalArgumentException if there are none, too many, a blank one, or two that
     *                                  give the engine the same text
     * @throws NullPointerException     if the list or a phrase is null
     */
    public static VoiceGrammar of(List<String> phrases) {
        Objects.requireNonNull(phrases, "phrases");
        if (phrases.isEmpty()) {
            throw new IllegalArgumentException("at least one phrase is needed");
        }
        if (phrases.size() > MAX_PHRASES) {
            throw new IllegalArgumentException(
                    "at most " + MAX_PHRASES + " phrases, got " + phrases.size());
        }
        List<String> originals = List.copyOf(phrases);
        List<String> texts = new ArrayList<>(originals.size());
        Map<String, String> seen = new HashMap<>();
        for (String phrase : originals) {
            String text = engineText(phrase);
            String clash = seen.putIfAbsent(text.toLowerCase(Locale.ROOT), phrase);
            if (clash != null) {
                throw new IllegalArgumentException(
                        "\"" + phrase + "\" and \"" + clash + "\" would sound the same");
            }
            texts.add(text);
        }
        return new VoiceGrammar(originals, List.copyOf(texts));
    }

    /**
     * The text the engine is given for {@code phrase}.
     *
     * @throws IllegalArgumentException if nothing speakable is left
     */
    static String engineText(String phrase) {
        String text = NOT_WORD.matcher(phrase).replaceAll(WORD_BREAK).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("phrase has no words: \"" + phrase + "\"");
        }
        return text;
    }

    /** The rule id the phrase at {@code index} is registered under. Never zero. */
    static int ruleIdOf(int index) {
        return index + 1;
    }

    /** The caller's phrase registered under {@code ruleId}, or empty for an id not ours. */
    public Optional<String> phraseForRuleId(long ruleId) {
        long index = ruleId - 1;
        if (index < 0 || index >= phrases.size()) {
            return Optional.empty();
        }
        return Optional.of(phrases.get((int) index));
    }

    /** The phrases as the caller passed them, in order. */
    public List<String> phrases() {
        return phrases;
    }

    /** The engine text for each phrase, index for index with {@link #phrases()}. */
    public List<String> engineTexts() {
        return engineTexts;
    }
}
