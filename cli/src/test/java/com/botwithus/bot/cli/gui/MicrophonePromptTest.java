package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import com.botwithus.bot.core.runtime.ScriptIdentity;
import com.botwithus.bot.core.voice.ConsentAnswer;
import com.botwithus.bot.core.voice.ConsentRequest;
import com.botwithus.bot.core.voice.MicrophoneDecision;
import com.botwithus.bot.core.voice.StoredConsent;
import com.botwithus.bot.core.voice.VoiceConsent;
import com.botwithus.bot.core.voice.VoiceConsentStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The microphone prompt's rules, without drawing it. */
class MicrophonePromptTest {

    private static final float FRAME = 1f / 60f;
    private static final ScriptIdentity WOODCUTTER = new ScriptIdentity("wc/com.example.Woodcutter", "Woodcutter");
    private static final ScriptIdentity FISHER = new ScriptIdentity("fish/com.example.Fisher", "Fisher");

    private final AtomicInteger listens = new AtomicInteger();
    private final VoiceInput mic = new VoiceInput() {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
            listens.incrementAndGet();
            return () -> { };
        }
    };
    private final VoiceConsentStore store = VoiceConsentStore.inMemory();
    private final VoiceConsent consent = new VoiceConsent(store);
    private final MicrophonePrompt prompt = new MicrophonePrompt(consent);

    MicrophonePromptTest() {
        consent.attachPrompt();
    }

    private ConsentRequest ask(ScriptIdentity script, List<String> phrases) {
        consent.forScript(mic, script).listen(phrases, h -> { });
        return consent.current().orElseThrow();
    }

    private Optional<MicrophoneDecision> decided() {
        return store.find(WOODCUTTER.key()).map(StoredConsent::decision);
    }

    /** Draws frames until the prompt has been on screen long enough to arm. */
    private void armed() {
        float shown = 0f;
        prompt.frame(FRAME);
        while (shown < MicrophonePrompt.ARM_SECONDS) {
            prompt.frame(FRAME);
            shown += FRAME;
        }
        assertTrue(prompt.isArmed());
    }

    @Test
    void aPressBeforeThePromptIsArmed_isIgnored() {
        ConsentRequest request = ask(WOODCUTTER, List.of("chop"));
        prompt.frame(FRAME);

        assertFalse(prompt.isArmed());
        assertFalse(prompt.choose(request, MicrophonePrompt.Choice.ALLOW));

        assertEquals(0, listens.get());
        assertEquals(Optional.of(request), consent.current());
    }

    @Test
    void allow_onceArmed_opensTheMicAndIsRemembered() {
        ConsentRequest request = ask(WOODCUTTER, List.of("chop"));
        armed();

        assertTrue(prompt.choose(request, MicrophonePrompt.Choice.ALLOW));

        assertEquals(1, listens.get());
        assertEquals(Optional.of(MicrophoneDecision.ALLOWED), decided());
    }

    @Test
    void close_isADismissal_thatRemembersNothingAndOpensNothing() {
        ConsentRequest request = ask(WOODCUTTER, List.of("chop"));
        armed();

        assertTrue(prompt.choose(request, MicrophonePrompt.Choice.CLOSE));

        assertEquals(0, listens.get());
        assertTrue(store.all().isEmpty());
        assertTrue(consent.current().isEmpty());
    }

    @Test
    void deny_isRememberedAndOpensNothing() {
        ConsentRequest request = ask(WOODCUTTER, List.of("chop"));
        armed();

        assertTrue(prompt.choose(request, MicrophonePrompt.Choice.DENY));

        assertEquals(0, listens.get());
        assertEquals(Optional.of(MicrophoneDecision.DENIED), decided());
    }

    @Test
    void theNextQuestion_startsUnarmed_soAClickMeantForTheLastCannotAnswerIt() {
        ConsentRequest first = ask(WOODCUTTER, List.of("chop"));
        consent.forScript(mic, FISHER).listen(List.of("fish"), h -> { });
        armed();
        assertTrue(prompt.choose(first, MicrophonePrompt.Choice.DENY));
        ConsentRequest second = consent.current().orElseThrow();
        assertEquals("Fisher", second.scriptName());

        assertEquals(Optional.of(second), prompt.frame(FRAME));
        assertFalse(prompt.isArmed());
        assertFalse(prompt.choose(second, MicrophonePrompt.Choice.ALLOW));
        assertEquals(0, listens.get());
    }

    @Test
    void anAnswerForAQuestionNoLongerOnScreen_isIgnored() {
        ConsentRequest first = ask(WOODCUTTER, List.of("chop"));
        armed();
        consent.answer(first, ConsentAnswer.DENY);

        assertFalse(prompt.choose(first, MicrophonePrompt.Choice.ALLOW));
        assertEquals(Optional.of(MicrophoneDecision.DENIED), decided());
    }

    @Test
    void noWayOutOfThePrompt_isAnAllow_exceptTheAllowButton() {
        for (MicrophonePrompt.Choice choice : MicrophonePrompt.Choice.values()) {
            if (choice != MicrophonePrompt.Choice.ALLOW) {
                assertNotEquals(ConsentAnswer.ALLOW, MicrophonePrompt.answerFor(choice), choice.name());
            }
        }
        assertEquals(ConsentAnswer.DISMISS, MicrophonePrompt.answerFor(MicrophonePrompt.Choice.CLOSE));
    }

    @Test
    void text_namesTheScriptAndWhereTheAudioGoes_andListsThePhrases() {
        MicrophonePrompt.Text text = MicrophonePrompt.text(ask(WOODCUTTER, List.of("chop", "drop logs")));

        assertTrue(text.body().startsWith("Woodcutter wants to use your microphone"), text.body());
        assertTrue(text.body().contains("on this PC"), text.body());
        assertEquals(WOODCUTTER.key(), text.scriptKey());
        assertEquals("It listens for these 2 phrases:", text.listLabel());
        assertEquals(List.of("“chop”", "“drop logs”"), text.phrases());
        assertEquals(Optional.empty(), text.moreLine());
    }

    @Test
    void text_capsALongList_andCountsTheRest() {
        int total = MicrophonePrompt.MAX_SHOWN_PHRASES + 5;
        List<String> phrases = IntStream.range(0, total).mapToObj(i -> "phrase " + i).toList();

        MicrophonePrompt.Text text = MicrophonePrompt.text(ask(WOODCUTTER, phrases));

        assertEquals(MicrophonePrompt.MAX_SHOWN_PHRASES, text.phrases().size());
        assertEquals(Optional.of("and 5 more"), text.moreLine());
        assertEquals("It listens for these " + total + " phrases:", text.listLabel());
    }

    @Test
    void text_forOnePhrase_isSingular() {
        assertEquals("It listens for this phrase:",
                MicrophonePrompt.text(ask(WOODCUTTER, List.of("chop"))).listLabel());
    }
}
