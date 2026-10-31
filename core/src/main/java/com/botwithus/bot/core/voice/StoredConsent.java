package com.botwithus.bot.core.voice;

import java.util.Objects;

/**
 * One remembered microphone decision.
 *
 * @param key         the script's {@link com.botwithus.bot.core.runtime.ScriptIdentity#key() key}
 * @param displayName the name the script had when the user decided, for the Settings list
 * @param decision    what the user decided
 */
public record StoredConsent(String key, String displayName, MicrophoneDecision decision) {

    public StoredConsent {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(decision, "decision");
    }
}
