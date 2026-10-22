package com.botwithus.bot.core.voice;

import java.time.Duration;
import java.util.List;

/**
 * The speech engine as {@link VoiceInputService} drives it.
 *
 * <p>Every method is called from the service's one engine thread, which lets an
 * implementation keep thread-affine state (a COM apartment) without locking.</p>
 */
interface RecognizerBackend {

    /** Whether the engine and an input are present. Starts nothing; never throws. */
    boolean probe();

    /**
     * Starts recognising {@code grammar}, opening the engine and the input first if they
     * are closed, and replacing any grammar already active.
     *
     * @throws RuntimeException if the engine or the input cannot be opened, or the grammar
     *                          is refused; the backend is then left stopped
     */
    void listen(VoiceGrammar grammar);

    /**
     * Waits up to {@code timeout} for the engine and returns what it recognised, possibly
     * nothing. Only called between {@link #listen} and {@link #stop}.
     */
    List<Recognition> await(Duration timeout);

    /** Stops recognising and releases the engine and the input. Safe when already stopped. */
    void stop();

    /** Releases everything the engine thread holds. The last call the backend sees. */
    void shutdown();
}
