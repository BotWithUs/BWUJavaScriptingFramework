package com.botwithus.bot.core.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoiceGrammarTest {

    @Test
    void of_plainPhrases_keepsTheirTextForTheEngine() {
        VoiceGrammar grammar = VoiceGrammar.of(List.of("enable banking", "set food to lobster"));

        assertEquals(List.of("enable banking", "set food to lobster"), grammar.engineTexts());
    }

    @Test
    void engineText_punctuationAndRunsOfSpace_becomeSingleWordBreaks() {
        assertEquals("stop", VoiceGrammar.engineText("  stop!  "));
        assertEquals("hello bot", VoiceGrammar.engineText("hello,   bot."));
        assertEquals("don't stop", VoiceGrammar.engineText("don't stop"));
        assertEquals("x-ray mode", VoiceGrammar.engineText("x-ray\tmode"));
    }

    @Test
    void engineText_sapiWordFormatCharacters_areEscapedToWordBreaks() {
        // "/display/lexical/pron;" is SAPI's word syntax; neither character may reach it.
        assertEquals("bank all", VoiceGrammar.engineText("/bank/all;"));
        assertEquals("fifty fifty", VoiceGrammar.engineText("fifty/fifty"));
    }

    @Test
    void engineText_nonAsciiLetters_areKept() {
        assertEquals("café über", VoiceGrammar.engineText("café über"));
    }

    @Test
    void of_phraseWithNoWords_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> VoiceGrammar.of(List.of("ok", "?!")));
        assertThrows(IllegalArgumentException.class, () -> VoiceGrammar.of(List.of("   ")));
    }

    @Test
    void of_phrasesThatSoundTheSame_areRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VoiceGrammar.of(List.of("Stop", "stop!")));

        assertEquals("\"stop!\" and \"Stop\" would sound the same", e.getMessage());
    }

    @Test
    void of_noPhrases_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> VoiceGrammar.of(List.of()));
    }

    @Test
    void of_tooManyPhrases_isRejected() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= VoiceGrammar.MAX_PHRASES; i++) {
            many.add("phrase " + Integer.toBinaryString(i).replace('0', 'o').replace('1', 'i'));
        }

        assertThrows(IllegalArgumentException.class, () -> VoiceGrammar.of(many));
    }

    @Test
    void of_nullListOrPhrase_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> VoiceGrammar.of(null));
        assertThrows(NullPointerException.class,
                () -> VoiceGrammar.of(Collections.unmodifiableList(Arrays.asList("a", null))));
    }

    @Test
    void phraseForRuleId_mapsBackToTheCallersOwnString() {
        String stop = new String("stop!");
        VoiceGrammar grammar = VoiceGrammar.of(List.of("hello bot", stop));

        assertSame(stop, grammar.phraseForRuleId(VoiceGrammar.ruleIdOf(1)).orElseThrow());
        assertEquals(Optional.of("hello bot"), grammar.phraseForRuleId(VoiceGrammar.ruleIdOf(0)));
    }

    @Test
    void phraseForRuleId_idOutsideTheGrammar_isEmpty() {
        VoiceGrammar grammar = VoiceGrammar.of(List.of("one", "two"));

        assertEquals(Optional.empty(), grammar.phraseForRuleId(0));
        assertEquals(Optional.empty(), grammar.phraseForRuleId(VoiceGrammar.ruleIdOf(2)));
        assertEquals(Optional.empty(), grammar.phraseForRuleId(-1));
    }

    @Test
    void of_copiesTheList_soLaterChangesDoNotLeakIn() {
        List<String> phrases = new ArrayList<>(List.of("one"));
        VoiceGrammar grammar = VoiceGrammar.of(phrases);
        phrases.add("two");

        assertEquals(List.of("one"), grammar.phrases());
    }
}
