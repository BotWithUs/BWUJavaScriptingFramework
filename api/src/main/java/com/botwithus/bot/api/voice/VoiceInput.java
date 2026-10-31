package com.botwithus.bot.api.voice;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Offline speech recognition constrained to a fixed list of phrases.
 *
 * <p>This is command-and-control recognition, not dictation: the recogniser only ever
 * reports one of the phrases passed to {@link #listen}. It runs on the speech engine the
 * operating system already ships, and nothing leaves the machine.</p>
 *
 * <p>Nothing runs and the microphone stays closed until {@code listen} is called. There is
 * one microphone and one active grammar per host: a new {@code listen}, from this script or
 * another, replaces the previous grammar, and the replaced session goes quiet.</p>
 *
 * <p><b>The user decides whether a script may listen.</b> The first time a script calls
 * {@code listen}, the host asks the user whether that script may use the microphone, and
 * remembers the answer. Until the user allows it the session hears nothing, and if the user
 * denies it, or closes the question without answering, it stays silent. A host with no
 * window to ask in (a headless or console host) refuses any script the user has not already
 * allowed. So never assume a session is live: a script whose voice commands are optional
 * should keep working without them, and should not treat silence as an error.</p>
 *
 * <p>Reach it from a script through
 * {@link com.botwithus.bot.api.ScriptContext#getVoiceInput()}. Sessions a script opens are
 * closed for it when it stops.</p>
 */
public interface VoiceInput {

    /**
     * A voice input with no recogniser behind it: {@link #available()} is false and
     * {@link #listen} returns a session that never hears anything. Returned where the host
     * has no speech support wired in, such as test contexts.
     */
    VoiceInput UNAVAILABLE = new VoiceInput() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
            Objects.requireNonNull(phrases, "phrases");
            Objects.requireNonNull(onHeard, "onHeard");
            return () -> { };
        }
    };

    /**
     * Whether a recogniser, a speech language pack and an audio input device are present.
     * It says nothing about whether the user has allowed this script to listen, and never
     * asks them.
     *
     * <p>Never throws; anything that prevents the check reports {@code false}. It asks the
     * operating system each time, so it can block for a moment. Call it once when the
     * script's UI opens, not every loop.</p>
     *
     * @return {@code true} if {@link #listen} can be expected to hear something
     */
    boolean available();

    /**
     * Starts listening for {@code phrases}, replacing whatever grammar was active, once the
     * user has allowed this script to use the microphone.
     *
     * <p>Returns at once, without waiting for the user. If they have not decided yet, the
     * returned session hears nothing until they allow it; the host asks them on the first
     * call. Their answer applies to the latest session the script still holds open, so
     * calling {@code listen} again while they decide replaces the phrases they are asked
     * about rather than asking twice. Closing the session, or the script stopping, before
     * they answer means nothing is ever heard from it. If they deny it, every session the
     * script opens stays silent until they reset the decision in the host's settings.</p>
     *
     * <p>{@code onHeard} runs on a host thread dedicated to voice callbacks, never on the
     * script's own loop thread and never on the thread that talks to the speech engine. All
     * sessions share that one thread, so a callback that blocks delays every later callback.
     * Keep it short: record the phrase (in a queue or a volatile field) and act on it from
     * {@code onLoop}, rather than driving the game from inside the callback.</p>
     *
     * <p>Phrases are matched as whole utterances. Punctuation is ignored and runs of
     * whitespace count as one space. Spell numbers as words ({@code "five"}, not
     * {@code "5"}) for reliable recognition.</p>
     *
     * <p>If recognition cannot start (for example when {@link #available()} is false), the
     * failure is logged and the returned session simply never hears anything.</p>
     *
     * @param phrases the phrases to recognise. There must be at least one; none may be blank,
     *                and no two may be the same once case, punctuation and spacing are ignored
     * @param onHeard called with each phrase heard while this session is the active one
     * @return the session; close it to stop listening. It may never hear anything if the user
     *         has not allowed this script
     * @throws IllegalArgumentException if {@code phrases} is empty, holds a blank phrase, or
     *                                  holds two phrases that would be indistinguishable
     * @throws NullPointerException     if {@code phrases}, a phrase, or {@code onHeard} is null
     */
    VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard);
}
