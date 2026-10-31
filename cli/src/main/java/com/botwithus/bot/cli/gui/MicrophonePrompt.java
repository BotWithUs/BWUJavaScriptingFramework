package com.botwithus.bot.cli.gui;

import com.botwithus.bot.core.voice.ConsentAnswer;
import com.botwithus.bot.core.voice.ConsentRequest;
import com.botwithus.bot.core.voice.VoiceConsent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules of the microphone prompt, apart from drawing it: which question is on screen,
 * what it says, when its buttons may be pressed, and what each way out answers.
 * {@link MicrophoneConsentModal} draws it. Render thread only.
 *
 * <p>Two rules protect consent. The buttons only take a press once the question has been
 * on screen for {@link #ARM_SECONDS}, so a click already on its way when the prompt appears
 * (or when the next script's question replaces the last) cannot answer a question the user
 * never saw. And closing the prompt without choosing is a {@link ConsentAnswer#DISMISS}:
 * the script is refused for this run, and nothing is remembered.</p>
 */
final class MicrophonePrompt {

    /** What the user did. */
    enum Choice { ALLOW, DENY, CLOSE }

    /**
     * What the prompt says about one question.
     *
     * @param title       the heading
     * @param body        who is asking and for what, and where the audio goes
     * @param scriptKey   the module and class the decision is remembered under
     * @param listLabel   the line over the phrases
     * @param phrases     the phrases shown by name, quoted, at most {@link #MAX_SHOWN_PHRASES}
     * @param morePhrases how many more the script asked for than are shown
     * @param footnote    where the answer goes, and what closing without one means
     */
    record Text(String title, String body, String scriptKey, String listLabel, List<String> phrases,
                int morePhrases, String footnote) {

        Text {
            phrases = List.copyOf(phrases);
        }

        /** "and N more" when phrases are left out, so the count is never hidden. */
        Optional<String> moreLine() {
            return morePhrases == 0 ? Optional.empty() : Optional.of("and " + morePhrases + " more");
        }
    }

    /** Phrases shown by name; the rest are counted. Keeps the prompt on screen for a long list. */
    static final int MAX_SHOWN_PHRASES = 8;
    /** The longest script name shown; the script chose it, so it is capped. */
    static final int MAX_NAME_CHARS = 48;
    /** The longest phrase shown. */
    static final int MAX_PHRASE_CHARS = 80;
    /** The longest key shown; cut from the front, so its distinguishing end stays. */
    static final int MAX_KEY_CHARS = 160;
    /** How long a question is on screen before its buttons take a press. */
    static final float ARM_SECONDS = 0.4f;

    static final String TITLE = "Allow microphone access?";
    static final String FOOTNOTE = "Your answer is remembered for this script. Change it any time "
            + "in Settings, under Scripts. Esc closes this without answering: the script stays "
            + "without the microphone until it next starts.";

    private final VoiceConsent consent;
    private ConsentRequest showing;
    private float shownSeconds;

    MicrophonePrompt(VoiceConsent consent) {
        this.consent = Objects.requireNonNull(consent, "consent");
    }

    /**
     * The question to draw this frame, if any. Call once per frame with the frame's
     * duration; a question that has just come on screen starts unarmed.
     */
    Optional<ConsentRequest> frame(float deltaSeconds) {
        Optional<ConsentRequest> current = consent.current();
        if (current.isEmpty()) {
            showing = null;
            return current;
        }
        if (current.get() != showing) {
            showing = current.get();
            shownSeconds = 0f;
        } else {
            shownSeconds += deltaSeconds;
        }
        return current;
    }

    /** Whether the buttons take a press yet. */
    boolean isArmed() {
        return showing != null && shownSeconds >= ARM_SECONDS;
    }

    /**
     * Answers the question on screen. Ignored before the prompt is armed, and when
     * {@code request} is no longer the one on screen.
     *
     * @return whether the answer was taken
     */
    boolean choose(ConsentRequest request, Choice choice) {
        if (!isArmed() || request != showing) {
            return false;
        }
        return consent.answer(request, answerFor(choice));
    }

    static ConsentAnswer answerFor(Choice choice) {
        return switch (choice) {
            case ALLOW -> ConsentAnswer.ALLOW;
            case DENY -> ConsentAnswer.DENY;
            case CLOSE -> ConsentAnswer.DISMISS;
        };
    }

    /** What the prompt says about {@code request}. */
    static Text text(ConsentRequest request) {
        List<String> phrases = request.phrases();
        int shown = Math.min(phrases.size(), MAX_SHOWN_PHRASES);
        List<String> quoted = phrases.subList(0, shown).stream()
                .map(p -> "“" + SafeText.line(p, MAX_PHRASE_CHARS) + "”").toList();
        String body = SafeText.line(request.scriptName(), MAX_NAME_CHARS) + " wants to use your microphone to listen for spoken phrases. "
                + "Speech is recognised on this PC, and no audio leaves it.";
        String label = phrases.size() == 1 ? "It listens for this phrase:"
                : "It listens for these " + phrases.size() + " phrases:";
        return new Text(TITLE, body, SafeText.lineKeepingTail(request.scriptKey(), MAX_KEY_CHARS), label, quoted, phrases.size() - shown, FOOTNOTE);
    }
}
