package com.botwithus.bot.api.voice;

/**
 * A grammar that {@link VoiceInput#listen} put in place.
 *
 * <p>Closing it stops recognition and releases the microphone, unless a later
 * {@code listen} has already replaced it, in which case closing it does nothing.
 * Idempotent and safe to call from any thread. Once {@code close} returns, the
 * session's callback is not started again; a call already running on the callback
 * thread at that moment is allowed to finish.</p>
 */
public interface VoiceSession extends AutoCloseable {

    @Override
    void close();
}
