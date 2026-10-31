package com.botwithus.bot.core.voice;

import java.util.Optional;

/** What the user decided about a script using the microphone, as it is remembered. */
public enum MicrophoneDecision {

    ALLOWED("allow"),
    DENIED("deny");

    private final String stored;

    MicrophoneDecision(String stored) {
        this.stored = stored;
    }

    /** The decision as the consent file writes it. */
    String stored() {
        return stored;
    }

    /**
     * The decision {@code text} names, compared exactly. Anything else, including a
     * differently cased or misspelt {@code allow}, is no decision, so a damaged file can
     * never read as consent.
     */
    static Optional<MicrophoneDecision> parse(String text) {
        for (MicrophoneDecision decision : values()) {
            if (decision.stored.equals(text)) {
                return Optional.of(decision);
            }
        }
        return Optional.empty();
    }
}
