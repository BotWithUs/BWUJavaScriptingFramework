package com.botwithus.bot.cli.alerts;

import java.util.Objects;

/** The outcome of {@link Integrations#saveSecret}. */
public sealed interface SecretChange {

    /** The secret was saved. */
    record Saved() implements SecretChange { }

    /** The field was blank, so the saved secret was removed. */
    record Cleared() implements SecretChange { }

    /**
     * The secret was not saved.
     *
     * @param reason what to fix, e.g. {@code The webhook URL must start with https://}; never the secret
     */
    record Refused(String reason) implements SecretChange {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
