package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import com.botwithus.bot.core.runtime.ScriptIdentity;
import com.botwithus.bot.core.runtime.ScriptVoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One script's view of the host's {@link VoiceInput}: it keeps the microphone closed until
 * the user has allowed the script, and remembers the session the script opened so the
 * runtime can close it when the script stops.
 *
 * <p><b>Consent.</b> {@code listen} returns a session at once in every case, but reaches the
 * host's voice input only when {@link VoiceConsent} says the script is allowed. With no
 * decision, the session waits silently while the user is asked; an Allow starts the latest
 * session the script still holds open, and a Deny or a dismissal leaves it silent. A
 * dismissal, or a host that cannot ask, refuses the script for this run only: this object
 * lives for one run.</p>
 *
 * <p><b>Scope.</b> Without this a stopped script's callback would keep firing on every
 * phrase heard, and could keep acting on the game after Stop, which is the same leak
 * {@code ScopedEventBus} closes for event listeners. Only the latest session is kept: the
 * host has one active grammar, so a new {@code listen} has already replaced the previous
 * one, and keeping every session a script ever opened would grow without bound for a UI
 * that re-listens on each mode change.</p>
 */
public final class ScopedVoiceInput implements ScriptVoice {

    private static final Logger log = LoggerFactory.getLogger(ScopedVoiceInput.class);

    /** What a {@code listen} may do, decided under {@link #lock}. */
    private enum Admission { OPEN, ASKING, DENIED_STORED, REFUSED_THIS_RUN, NO_PROMPT }

    private final VoiceInput delegate;
    private final ScriptIdentity script;
    private final VoiceConsent consent;
    private final AtomicReference<Tracked> latest = new AtomicReference<>();
    private final Consumer<ConsentAnswer> waiter = this::onAnswer;
    private final AtomicBoolean hasLoggedRefusal = new AtomicBoolean();
    private final Object lock = new Object();
    /** Guarded by {@link #lock}: the question this run is waiting on, if any. */
    private ConsentRequest asking;
    /** Guarded by {@link #lock}: allowed in this run, which holds even if the store could not save it. */
    private boolean isAllowedThisRun;
    /** Guarded by {@link #lock}: refused in this run by a Deny, a dismissal, or no way to ask. */
    private boolean isRefusedThisRun;

    ScopedVoiceInput(VoiceInput delegate, ScriptIdentity script, VoiceConsent consent) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.script = Objects.requireNonNull(script, "script");
        this.consent = Objects.requireNonNull(consent, "consent");
    }

    /** A plain capability check: never asks the user. */
    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
        // Validate as the host would, so a bad call throws here whatever the consent.
        VoiceGrammar.of(phrases);
        Objects.requireNonNull(onHeard, "onHeard");
        Tracked session = new Tracked(List.copyOf(phrases), onHeard);
        Tracked previous = latest.getAndSet(session);
        if (previous != null) {
            previous.close();
        }
        if (delegate == VoiceInput.UNAVAILABLE) {
            // Nothing could ever be heard, so there is nothing to ask the user about.
            return session;
        }
        consent.track(this);
        admit(session);
        return session;
    }

    /**
     * Closes the session this script left open, if any, and withdraws its question.
     * Called from the runner's cleanup; repeatable.
     */
    @Override
    public void closeAll() {
        Tracked open = latest.getAndSet(null);
        if (open != null) {
            open.close();
        }
        ConsentRequest pending;
        synchronized (lock) {
            pending = asking;
            asking = null;
        }
        if (pending != null) {
            consent.withdraw(pending, waiter);
        }
        consent.untrack(this);
    }

    String scriptKey() {
        return script.key();
    }

    /** The user reset this script's decision: stop hearing now, and ask on the next {@code listen}. */
    void revoke() {
        synchronized (lock) {
            isAllowedThisRun = false;
            isRefusedThisRun = false;
        }
        hasLoggedRefusal.set(false);
        Tracked open = latest.getAndSet(null);
        if (open != null && open.closeSession()) {
            log.info("Voice input for {} stopped: its microphone decision was reset", script.displayName());
        }
    }

    private void admit(Tracked session) {
        Admission admission;
        synchronized (lock) {
            admission = admission(session.phrases);
        }
        switch (admission) {
            case OPEN -> session.start(delegate);
            case ASKING, REFUSED_THIS_RUN -> { }
            case DENIED_STORED -> logRefusal("the user denied it. Reset it in Settings > Scripts to be asked again");
            case NO_PROMPT -> logRefusal("this host has no prompt to ask the user with. "
                    + "Allow it once from the GUI host; the decision is remembered");
        }
    }

    /** Decides what {@code listen} may do. Caller holds {@link #lock}. */
    private Admission admission(List<String> phrases) {
        Optional<MicrophoneDecision> stored = consent.stored(script);
        if (stored.isPresent()) {
            return stored.get() == MicrophoneDecision.ALLOWED ? Admission.OPEN : Admission.DENIED_STORED;
        }
        if (isAllowedThisRun) {
            return Admission.OPEN;
        }
        if (isRefusedThisRun) {
            return Admission.REFUSED_THIS_RUN;
        }
        if (asking != null) {
            asking.setPhrases(phrases);
            return Admission.ASKING;
        }
        asking = consent.ask(script, phrases, waiter).orElse(null);
        if (asking == null) {
            isRefusedThisRun = true;
            return Admission.NO_PROMPT;
        }
        return Admission.ASKING;
    }

    /** The user answered this script's question. Runs on the answering thread; returns promptly. */
    private void onAnswer(ConsentAnswer answer) {
        synchronized (lock) {
            asking = null;
            if (answer == ConsentAnswer.ALLOW) {
                isAllowedThisRun = true;
            } else {
                isRefusedThisRun = true;
            }
        }
        switch (answer) {
            case ALLOW -> {
                Tracked open = latest.get();
                if (open != null) {
                    open.start(delegate);
                }
            }
            case DENY -> logRefusal("the user denied it");
            case DISMISS -> logRefusal("the prompt was closed without an answer; it asks again on the next run");
        }
    }

    private void logRefusal(String why) {
        if (hasLoggedRefusal.compareAndSet(false, true)) {
            log.info("Voice input for {} stays off: {}", script.displayName(), why);
        }
    }

    /**
     * The session handed to the script. It reaches the host's voice input only once
     * {@link #start}ed, which happens at most once and never after it was closed.
     */
    private final class Tracked implements VoiceSession {

        private final List<String> phrases;
        private final Consumer<Heard> onHeard;
        /** Guarded by {@code this}; null until started. */
        private VoiceSession inner;
        /** Guarded by {@code this}. */
        private boolean isClosed;

        Tracked(List<String> phrases, Consumer<Heard> onHeard) {
            this.phrases = phrases;
            this.onHeard = onHeard;
        }

        /** Opens the microphone for this session, unless it was closed or already started. */
        synchronized void start(VoiceInput host) {
            if (isClosed || inner != null) {
                return;
            }
            inner = host.listen(phrases, onHeard);
        }

        @Override
        public void close() {
            closeSession();
        }

        /** @return whether this call closed a session that had reached the host */
        boolean closeSession() {
            latest.compareAndSet(this, null);
            VoiceSession open;
            synchronized (this) {
                if (isClosed) {
                    return false;
                }
                isClosed = true;
                open = inner;
            }
            if (open == null) {
                return false;
            }
            open.close();
            return true;
        }
    }
}
