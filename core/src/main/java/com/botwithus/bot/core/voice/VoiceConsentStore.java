package com.botwithus.bot.core.voice;

import java.util.List;
import java.util.Optional;

/**
 * Where microphone decisions are remembered, keyed by script.
 *
 * <p>Never throws for a store it cannot read or write. A store that cannot be read answers
 * "no decision" for every script, so the user is asked again rather than a script being let
 * in; a write that fails is logged, and the decision still holds for this run.</p>
 */
public interface VoiceConsentStore {

    /** The decision remembered for the script with {@code key}, if there is one. */
    Optional<StoredConsent> find(String key);

    /** Every remembered decision, ordered by script name. */
    List<StoredConsent> all();

    /** Remembers {@code consent}, replacing any decision for the same script. */
    void save(StoredConsent consent);

    /** Forgets the decision for the script with {@code key}, so it is asked again. */
    void forget(String key);

    /** Writes anything not yet saved, for a store that may defer writes. Called when the host closes. */
    default void flush() {
    }

    /** A store that remembers for as long as the process runs. */
    static VoiceConsentStore inMemory() {
        return new MemoryConsentStore();
    }
}
