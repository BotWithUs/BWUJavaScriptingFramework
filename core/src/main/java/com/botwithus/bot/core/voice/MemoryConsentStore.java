package com.botwithus.bot.core.voice;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Microphone decisions kept until the process exits. */
final class MemoryConsentStore implements VoiceConsentStore {

    /** Orders the Settings list: by the name the user sees, then by key to break ties. */
    static final Comparator<StoredConsent> BY_NAME =
            Comparator.comparing(StoredConsent::displayName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(StoredConsent::key);

    private final Map<String, StoredConsent> decisions = new HashMap<>();

    @Override
    public synchronized Optional<StoredConsent> find(String key) {
        return Optional.ofNullable(decisions.get(key));
    }

    @Override
    public synchronized List<StoredConsent> all() {
        return decisions.values().stream().sorted(BY_NAME).toList();
    }

    @Override
    public synchronized void save(StoredConsent consent) {
        decisions.put(consent.key(), consent);
    }

    @Override
    public synchronized void forget(String key) {
        decisions.remove(key);
    }
}
