package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.core.runtime.ScriptIdentity;
import com.botwithus.bot.core.runtime.ScriptVoice;
import com.botwithus.bot.core.runtime.VoiceGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Whether each script may use the microphone: the remembered decisions, and the questions
 * waiting for the user. One per host, shared by every connection, because there is one
 * microphone and one user to ask.
 *
 * <p><b>The rule.</b> A script's {@code listen} reaches the host's voice input only if the
 * user allowed that script, now or on an earlier run. With no decision remembered, the
 * script's first {@code listen} raises a question; while none is answered it hears nothing.
 * A host whose front-end has not {@linkplain #attachPrompt() attached a prompt} cannot ask,
 * so there an undecided script stays refused.</p>
 *
 * <p><b>The queue.</b> Questions are shown one at a time, oldest first. {@link #current()} is
 * the one to show; {@link #answer} takes it off the queue, remembers an Allow or a Deny,
 * and tells every run of that script that is waiting. Answers run the waiters on the
 * answering thread (the render thread, in the GUI), so they only record state and hand
 * the grammar to the voice input, which returns at once.</p>
 */
public final class VoiceConsent implements VoiceGate {

    private static final Logger log = LoggerFactory.getLogger(VoiceConsent.class);

    private final VoiceConsentStore store;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}; the head is the question on screen. */
    private final Deque<ConsentRequest> queue = new ArrayDeque<>();
    /** Every live script gate, so a reset can silence the script it applies to at once. */
    private final Set<ScopedVoiceInput> gates = ConcurrentHashMap.newKeySet();
    /** Guarded by {@link #lock}. */
    private boolean hasPrompt;

    public VoiceConsent(VoiceConsentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Consent with nothing remembered and no prompt: every script is refused. The runtime's
     * default, so a host that wires no consent cannot open the microphone for a script.
     */
    public static VoiceConsent refusingAll() {
        return new VoiceConsent(VoiceConsentStore.inMemory());
    }

    @Override
    public ScriptVoice forScript(VoiceInput shared, ScriptIdentity script) {
        return new ScopedVoiceInput(shared, script, this);
    }

    // ── The front-end's side ──────────────────────────────────────────────

    /** Declares that a front-end will show {@link #current()} and {@link #answer} it. */
    public void attachPrompt() {
        synchronized (lock) {
            hasPrompt = true;
        }
    }

    /**
     * Declares that nothing will show questions any more, and closes every waiting one
     * without an answer: those scripts are refused for this run.
     */
    public void detachPrompt() {
        List<ConsentRequest> dropped;
        synchronized (lock) {
            hasPrompt = false;
            dropped = List.copyOf(queue);
        }
        for (ConsentRequest request : dropped) {
            answer(request, ConsentAnswer.DISMISS);
        }
    }

    /** The question to show now, if any. */
    public Optional<ConsentRequest> current() {
        synchronized (lock) {
            return Optional.ofNullable(queue.peekFirst());
        }
    }

    /**
     * The user's answer to {@code request}. An Allow or a Deny is remembered; a dismissal is
     * not. Ignored if {@code request} is no longer the one on screen, for example because
     * the script stopped while it was showing.
     *
     * @return whether the answer was taken
     */
    public boolean answer(ConsentRequest request, ConsentAnswer answer) {
        List<Consumer<ConsentAnswer>> waiters;
        synchronized (lock) {
            if (queue.peekFirst() != request) {
                return false;
            }
            queue.removeFirst();
            waiters = List.copyOf(request.waiters());
            request.waiters().clear();
        }
        remember(request, answer);
        for (Consumer<ConsentAnswer> waiter : waiters) {
            waiter.accept(answer);
        }
        return true;
    }

    /** Every remembered decision, for the Settings list. */
    public List<StoredConsent> decisions() {
        return store.all();
    }

    /**
     * Forgets the decision for the script with {@code key}. A run of it that is listening
     * stops hearing at once, and its next {@code listen} asks again.
     */
    public void reset(String key) {
        store.forget(key);
        log.info("Microphone decision for {} reset; it will be asked again", key);
        for (ScopedVoiceInput gate : gates) {
            if (gate.scriptKey().equals(key)) {
                gate.revoke();
            }
        }
    }

    // ── The script gates' side ────────────────────────────────────────────

    Optional<MicrophoneDecision> stored(ScriptIdentity script) {
        return store.find(script.key()).map(StoredConsent::decision);
    }

    /**
     * Queues a question for {@code script}, or joins the one already waiting for it.
     *
     * @return the question, or empty when no front-end can ask
     */
    Optional<ConsentRequest> ask(ScriptIdentity script, List<String> phrases, Consumer<ConsentAnswer> waiter) {
        synchronized (lock) {
            if (!hasPrompt) {
                return Optional.empty();
            }
            ConsentRequest request = waitingFor(script.key()).orElseGet(() -> enqueue(script, phrases));
            request.setPhrases(phrases);
            request.waiters().add(waiter);
            return Optional.of(request);
        }
    }

    /** Takes {@code waiter} off {@code request}; a question nobody waits on is withdrawn. */
    void withdraw(ConsentRequest request, Consumer<ConsentAnswer> waiter) {
        synchronized (lock) {
            request.waiters().remove(waiter);
            if (request.waiters().isEmpty()) {
                queue.remove(request);
            }
        }
    }

    void track(ScopedVoiceInput gate) {
        gates.add(gate);
    }

    void untrack(ScopedVoiceInput gate) {
        gates.remove(gate);
    }

    /** Guarded by {@link #lock}. */
    private Optional<ConsentRequest> waitingFor(String key) {
        return queue.stream().filter(r -> r.scriptKey().equals(key)).findFirst();
    }

    /** Guarded by {@link #lock}. */
    private ConsentRequest enqueue(ScriptIdentity script, List<String> phrases) {
        ConsentRequest request = new ConsentRequest(script, phrases);
        queue.addLast(request);
        log.info("{} asked to use the microphone; waiting for the user", script.displayName());
        return request;
    }

    private void remember(ConsentRequest request, ConsentAnswer answer) {
        Optional<MicrophoneDecision> decision = switch (answer) {
            case ALLOW -> Optional.of(MicrophoneDecision.ALLOWED);
            case DENY -> Optional.of(MicrophoneDecision.DENIED);
            case DISMISS -> Optional.empty();
        };
        decision.ifPresent(d -> store.save(new StoredConsent(request.scriptKey(), request.scriptName(), d)));
    }
}
