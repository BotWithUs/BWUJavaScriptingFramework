package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Per-script view of the host's {@link VoiceInput} that remembers the session the script
 * opened, so the runtime can close it when the script stops.
 *
 * <p>Without this a stopped script's callback would keep firing on every phrase heard, and
 * could keep acting on the game after Stop, which is the same leak {@code ScopedEventBus}
 * closes for event listeners.</p>
 *
 * <p>Only the latest session is kept. The host has one active grammar, so a new
 * {@code listen} has already replaced the previous one, and keeping every session a script
 * ever opened would grow without bound for a UI that re-listens on each mode change.</p>
 */
public final class ScopedVoiceInput implements VoiceInput {

    private final VoiceInput delegate;
    private final AtomicReference<Tracked> latest = new AtomicReference<>();

    public ScopedVoiceInput(VoiceInput delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
        Tracked session = new Tracked(delegate.listen(phrases, onHeard));
        Tracked previous = latest.getAndSet(session);
        if (previous != null) {
            previous.inner.close();
        }
        return session;
    }

    /** Closes the session this script left open, if any. Called from the runner's cleanup; repeatable. */
    public void closeAll() {
        Tracked open = latest.getAndSet(null);
        if (open != null) {
            open.inner.close();
        }
    }

    /** A delegate session that forgets itself from {@link #latest} when closed. */
    private final class Tracked implements VoiceSession {

        private final VoiceSession inner;

        Tracked(VoiceSession inner) {
            this.inner = inner;
        }

        @Override
        public void close() {
            latest.compareAndSet(this, null);
            inner.close();
        }
    }
}
