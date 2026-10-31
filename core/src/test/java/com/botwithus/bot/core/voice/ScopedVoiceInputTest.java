package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.runtime.ScriptIdentity;
import com.botwithus.bot.core.runtime.ScriptVoice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The consent gate in front of the host's voice input. {@link RecordingMic} stands in for
 * the host's voice input and records every call that would open the microphone; the test
 * plays the GUI's prompt through {@link VoiceConsent#attachPrompt}, {@code current} and
 * {@code answer}.
 */
class ScopedVoiceInputTest {

    private static final ScriptIdentity WOODCUTTER = new ScriptIdentity("wc/com.example.Woodcutter", "Woodcutter");
    private static final ScriptIdentity FISHER = new ScriptIdentity("fish/com.example.Fisher", "Fisher");

    private final RecordingMic mic = new RecordingMic();
    private final VoiceConsentStore store = VoiceConsentStore.inMemory();
    private final VoiceConsent consent = new VoiceConsent(store);

    private ScriptVoice gate(ScriptIdentity script) {
        return consent.forScript(mic, script);
    }

    private Optional<MicrophoneDecision> decided() {
        return store.find(WOODCUTTER.key()).map(StoredConsent::decision);
    }

    private ConsentRequest showing() {
        return consent.current().orElseThrow(() -> new AssertionError("no question is on screen"));
    }

    @Nested
    @DisplayName("the microphone is not reached")
    class NotReached {

        @Test
        void noDecision_noPrompt_refusesAndNeverAsks() {
            ScriptVoice gate = gate(WOODCUTTER);

            gate.listen(List.of("chop"), h -> { });
            gate.listen(List.of("chop"), h -> { });

            assertEquals(List.of(), mic.listens);
            assertTrue(consent.current().isEmpty(), "a host that cannot ask must not queue a question");
            assertTrue(store.all().isEmpty(), "refusing for want of a prompt is not a decision");
        }

        @Test
        void noDecision_withPrompt_returnsAtOnceAndStaysSilentWhileAsking() {
            consent.attachPrompt();

            VoiceSession session = gate(WOODCUTTER).listen(List.of("chop"), h -> { });

            assertTrue(session != null);
            assertEquals(List.of(), mic.listens);
            assertEquals("Woodcutter", showing().scriptName());
        }

        @Test
        void deny_isRemembered_andNeitherThisRunNorTheNextReachesTheMic() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });

            assertTrue(consent.answer(showing(), ConsentAnswer.DENY));
            gate.listen(List.of("chop"), h -> { });
            gate(WOODCUTTER).listen(List.of("chop"), h -> { });

            assertEquals(List.of(), mic.listens);
            assertTrue(consent.current().isEmpty(), "a remembered Deny asks no further question");
            assertEquals(Optional.of(MicrophoneDecision.DENIED), decided());
        }

        @Test
        void dismiss_refusesThisRunOnly_andIsNotRemembered() {
            consent.attachPrompt();
            ScriptVoice thisRun = gate(WOODCUTTER);
            thisRun.listen(List.of("chop"), h -> { });

            consent.answer(showing(), ConsentAnswer.DISMISS);
            thisRun.listen(List.of("chop"), h -> { });

            assertEquals(List.of(), mic.listens);
            assertTrue(consent.current().isEmpty(), "the same run is not asked again");
            assertTrue(store.all().isEmpty(), "dismissing is not a decision");

            gate(WOODCUTTER).listen(List.of("chop"), h -> { });
            assertEquals("Woodcutter", showing().scriptName(), "the next run is asked again");
            assertEquals(List.of(), mic.listens);
        }

        @Test
        void closedPrompt_refusesWhatWasWaiting() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });

            consent.detachPrompt();

            assertTrue(consent.current().isEmpty());
            assertEquals(List.of(), mic.listens);
            assertTrue(store.all().isEmpty());
        }

        @Test
        void available_isAPlainCheck_thatNeverAsks() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);

            assertFalse(gate.available());
            mic.isAvailable = true;
            assertTrue(gate.available());

            assertTrue(consent.current().isEmpty());
            assertEquals(List.of(), mic.listens);
        }

        @Test
        void aBadPhraseListStillThrows_beforeAnythingIsAsked() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);

            assertThrows(IllegalArgumentException.class, () -> gate.listen(List.of(), h -> { }));
            assertThrows(NullPointerException.class, () -> gate.listen(List.of("chop"), null));

            assertTrue(consent.current().isEmpty());
        }

        @Test
        void anUnavailableHost_isNeverAskedAbout() {
            consent.attachPrompt();

            consent.forScript(VoiceInput.UNAVAILABLE, WOODCUTTER).listen(List.of("chop"), h -> { }).close();

            assertTrue(consent.current().isEmpty());
        }
    }

    @Nested
    @DisplayName("Allow")
    class Allow {

        @Test
        void allow_startsTheWaitingSessionOnce_andIsRemembered() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });

            consent.answer(showing(), ConsentAnswer.ALLOW);

            assertEquals(List.of(List.of("chop")), mic.listens);
            assertEquals(Optional.of(MicrophoneDecision.ALLOWED), decided());
        }

        @Test
        void aRememberedAllow_reachesTheMicWithoutAsking() {
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));

            gate(WOODCUTTER).listen(List.of("chop"), h -> { });

            assertEquals(List.of(List.of("chop")), mic.listens);
            assertTrue(consent.current().isEmpty());
        }

        @Test
        void listenAgainAfterAllow_replacesTheGrammarAsDocumented() {
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            ScriptVoice gate = gate(WOODCUTTER);

            gate.listen(List.of("one"), h -> { });
            gate.listen(List.of("two"), h -> { });
            assertEquals(1, mic.closes.get(), "the replaced session is closed");

            gate.closeAll();
            assertEquals(2, mic.closes.get());
            gate.closeAll();
            assertEquals(2, mic.closes.get(), "closeAll is repeatable");
        }

        @Test
        void closeAll_skipsSessionsTheScriptAlreadyClosed() {
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            ScriptVoice gate = gate(WOODCUTTER);
            VoiceSession session = gate.listen(List.of("one"), h -> { });
            session.close();
            session.close();

            gate.closeAll();

            assertEquals(1, mic.closes.get());
        }

        @Test
        void callbacksReachTheScript_onceAllowed() {
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            List<Heard> heard = new CopyOnWriteArrayList<>();

            gate(WOODCUTTER).listen(List.of("chop"), heard::add);
            mic.callbacks.getFirst().accept(new Heard("chop", 1f));

            assertEquals(List.of(new Heard("chop", 1f)), heard);
        }
    }

    @Nested
    @DisplayName("races while the question is on screen")
    class Races {

        @Test
        void scriptClosesTheSession_thenAllow_opensNothing() {
            consent.attachPrompt();
            VoiceSession session = gate(WOODCUTTER).listen(List.of("chop"), h -> { });

            session.close();
            consent.answer(showing(), ConsentAnswer.ALLOW);

            assertEquals(List.of(), mic.listens);
        }

        @Test
        void scriptStops_withdrawsTheQuestion_andALateAnswerIsIgnored() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });
            ConsentRequest onScreen = showing();

            gate.closeAll();

            assertTrue(consent.current().isEmpty(), "a stopped script's question is taken down");
            assertFalse(consent.answer(onScreen, ConsentAnswer.ALLOW), "an answer to a withdrawn question");
            assertEquals(List.of(), mic.listens);
            assertTrue(store.all().isEmpty());
        }

        @Test
        void listenAgainWhileAsking_keepsOneQuestion_andTheLatestPhrasesWin() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            VoiceSession first = gate.listen(List.of("chop"), h -> { });
            gate.listen(List.of("chop", "drop logs"), h -> { });

            ConsentRequest onScreen = showing();
            assertEquals(List.of("chop", "drop logs"), onScreen.phrases(), "the prompt shows what is consented to");
            consent.answer(onScreen, ConsentAnswer.ALLOW);

            assertEquals(List.of(List.of("chop", "drop logs")), mic.listens, "only the latest session starts");
            assertTrue(consent.current().isEmpty(), "one question, not two");
            first.close();
            assertEquals(0, mic.closes.get(), "the replaced session never reached the mic");
        }

        @Test
        void twoScriptsWaiting_areAskedOneAtATime_andEachAnswerIsTheirOwn() {
            consent.attachPrompt();
            gate(WOODCUTTER).listen(List.of("chop"), h -> { });
            gate(FISHER).listen(List.of("fish"), h -> { });

            assertEquals("Woodcutter", showing().scriptName());
            consent.answer(showing(), ConsentAnswer.ALLOW);
            assertEquals(List.of(List.of("chop")), mic.listens, "allowing one script opens nothing for the other");

            assertEquals("Fisher", showing().scriptName());
            consent.answer(showing(), ConsentAnswer.DENY);
            assertEquals(List.of(List.of("chop")), mic.listens);
            assertTrue(consent.current().isEmpty());
        }

        @Test
        void theSameScriptOnTwoClients_sharesOneQuestion() {
            consent.attachPrompt();
            ScriptVoice clientA = gate(WOODCUTTER);
            ScriptVoice clientB = gate(WOODCUTTER);
            clientA.listen(List.of("chop"), h -> { });
            clientB.listen(List.of("chop"), h -> { });

            consent.answer(showing(), ConsentAnswer.ALLOW);

            assertTrue(consent.current().isEmpty(), "one question for both runs");
            assertEquals(2, mic.listens.size(), "both runs start on the one answer");
        }

        @Test
        void oneClientStops_theOtherStillWaits() {
            consent.attachPrompt();
            ScriptVoice clientA = gate(WOODCUTTER);
            ScriptVoice clientB = gate(WOODCUTTER);
            clientA.listen(List.of("chop"), h -> { });
            clientB.listen(List.of("chop"), h -> { });

            clientA.closeAll();
            consent.answer(showing(), ConsentAnswer.ALLOW);

            assertEquals(List.of(List.of("chop")), mic.listens);
        }

        @Test
        void aRememberedAllowFromAnotherClient_letsAWaitingRunIn() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });
            consent.answer(showing(), ConsentAnswer.ALLOW);

            ScriptVoice later = gate(WOODCUTTER);
            later.listen(List.of("chop"), h -> { });

            assertEquals(2, mic.listens.size());
            assertTrue(consent.current().isEmpty());
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        void resetAnAllow_silencesTheListeningRun_andTheNextListenAsksAgain() {
            consent.attachPrompt();
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });
            assertEquals(1, mic.listens.size());

            consent.reset(WOODCUTTER.key());

            assertEquals(1, mic.closes.get(), "the open session is closed at once");
            assertTrue(store.all().isEmpty());
            gate.listen(List.of("chop"), h -> { });
            assertEquals(1, mic.listens.size(), "no longer allowed");
            assertEquals("Woodcutter", showing().scriptName());
        }

        @Test
        void resetADeny_asksAgainOnTheNextListen() {
            consent.attachPrompt();
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.DENIED));
            ScriptVoice gate = gate(WOODCUTTER);
            gate.listen(List.of("chop"), h -> { });
            assertTrue(consent.current().isEmpty());

            consent.reset(WOODCUTTER.key());
            gate.listen(List.of("chop"), h -> { });

            assertEquals("Woodcutter", showing().scriptName());
        }

        @Test
        void resetLeavesOtherScriptsAlone() {
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            store.save(new StoredConsent(FISHER.key(), "Fisher", MicrophoneDecision.ALLOWED));
            gate(WOODCUTTER).listen(List.of("chop"), h -> { });
            gate(FISHER).listen(List.of("fish"), h -> { });

            consent.reset(FISHER.key());

            assertEquals(1, mic.closes.get());
            assertEquals(List.of(WOODCUTTER.key()), store.all().stream().map(StoredConsent::key).toList());
        }
    }

    @Nested
    @DisplayName("after the script stopped")
    class Stopped {

        @Test
        void listenAfterCloseAll_isDead_andRaisesNoQuestion() {
            consent.attachPrompt();
            ScriptVoice gate = gate(WOODCUTTER);
            gate.closeAll();

            gate.listen(List.of("chop"), h -> { });

            assertTrue(consent.current().isEmpty(), "a stopped script must not raise a question");
            assertEquals(List.of(), mic.listens);
        }

        @Test
        void listenAfterCloseAll_staysDead_evenWithARememberedAllow() {
            store.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            ScriptVoice gate = gate(WOODCUTTER);
            gate.closeAll();

            gate.listen(List.of("chop"), h -> { });

            assertEquals(List.of(), mic.listens);
        }

        @Test
        void anAllowForTheSameScriptOnAnotherClient_doesNotReachAStoppedRun() {
            consent.attachPrompt();
            ScriptVoice stopped = gate(WOODCUTTER);
            ScriptVoice running = gate(WOODCUTTER);
            stopped.listen(List.of("chop"), h -> { });
            running.listen(List.of("chop"), h -> { });
            stopped.closeAll();
            stopped.listen(List.of("chop"), h -> { });

            consent.answer(showing(), ConsentAnswer.ALLOW);

            assertEquals(List.of(List.of("chop")), mic.listens, "only the running client starts");
        }
    }

    @Nested
    @DisplayName("an Allow that lands on a session closed in the meantime")
    class ClosedMeanwhile {

        @Test
        void closedWhileItWasBeingAdmitted_neverReachesTheMic() {
            HookedStore hooked = new HookedStore();
            VoiceConsent consentWithHook = new VoiceConsent(hooked);
            hooked.inner.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            ScriptVoice gate = consentWithHook.forScript(mic, WOODCUTTER);
            // The script's stop lands between "this script is allowed" and opening the mic.
            hooked.onFind = gate::closeAll;

            gate.listen(List.of("chop"), h -> { });

            assertEquals(List.of(), mic.listens);
        }

        @Test
        void resetWhileItWasBeingAdmitted_neverReachesTheMic() {
            HookedStore hooked = new HookedStore();
            VoiceConsent consentWithHook = new VoiceConsent(hooked);
            hooked.inner.save(new StoredConsent(WOODCUTTER.key(), "Woodcutter", MicrophoneDecision.ALLOWED));
            ScriptVoice gate = consentWithHook.forScript(mic, WOODCUTTER);
            // The user's Reset lands between "this script is allowed" and opening the mic.
            hooked.onFind = () -> consentWithHook.reset(WOODCUTTER.key());

            gate.listen(List.of("chop"), h -> { });

            assertEquals(List.of(), mic.listens);
        }
    }

    @Test
    void refusingAll_neverReachesTheMic_evenWithAPrompt() {
        VoiceConsent none = VoiceConsent.refusingAll();

        none.forScript(mic, WOODCUTTER).listen(List.of("chop"), h -> { });

        assertEquals(List.of(), mic.listens);
        assertTrue(none.current().isEmpty());
    }

    @Test
    void scriptContext_defaultsToUnavailable_andWithVoiceInputSurvivesOtherWithers() {
        ScriptContextImpl bare = new ScriptContextImpl(mock(GameAPI.class), new EventBusImpl(), new MessageBusImpl());
        assertSame(VoiceInput.UNAVAILABLE, bare.getVoiceInput());
        ScriptVoice scoped = gate(WOODCUTTER);

        ScriptContextImpl bound = bare.withVoiceInput(scoped).withStopSignal(() -> true).withDisplayName(null);

        assertSame(scoped, bound.getVoiceInput());
    }

    @Test
    void unavailable_neverHears_andRejectsNulls() {
        assertFalse(VoiceInput.UNAVAILABLE.available());
        VoiceInput.UNAVAILABLE.listen(List.of("x"), h -> { }).close();
        assertThrows(NullPointerException.class, () -> VoiceInput.UNAVAILABLE.listen(List.of("x"), null));
    }

    /**
     * A store that runs {@link #onFind} once, inside the first lookup that finds a decision:
     * the moment the gate has decided the script is allowed but has not yet opened the mic.
     */
    private static final class HookedStore implements VoiceConsentStore {

        final VoiceConsentStore inner = VoiceConsentStore.inMemory();
        volatile Runnable onFind = () -> { };

        @Override
        public Optional<StoredConsent> find(String key) {
            Optional<StoredConsent> found = inner.find(key);
            Runnable hook = onFind;
            onFind = () -> { };
            found.ifPresent(f -> hook.run());
            return found;
        }

        @Override
        public List<StoredConsent> all() {
            return inner.all();
        }

        @Override
        public void save(StoredConsent consent) {
            inner.save(consent);
        }

        @Override
        public void forget(String key) {
            inner.forget(key);
        }
    }

    /** The host's voice input as far as the gate can tell: records every call that would open the mic. */
    private static final class RecordingMic implements VoiceInput {

        final List<List<String>> listens = new CopyOnWriteArrayList<>();
        final List<Consumer<Heard>> callbacks = new CopyOnWriteArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        volatile boolean isAvailable;

        @Override
        public boolean available() {
            return isAvailable;
        }

        @Override
        public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
            listens.add(List.copyOf(phrases));
            callbacks.add(onHeard);
            return closes::incrementAndGet;
        }
    }
}
