package com.botwithus.bot.api.event;

/**
 * Fired when a client variable (varc) changes value. Distinct from
 * {@link VarChangeEvent} — varp and varc live in separate id namespaces and
 * separate VarDomain instances on the producer side, so subscribers must be
 * able to discriminate.
 *
 * @param varId     the varc ID that changed
 * @param oldValue  the previous value
 * @param newValue  the new value
 * @param timestamp event creation time in milliseconds since epoch
 */
public record VarcChangeEvent(int varId, int oldValue, int newValue, long timestamp) implements GameEvent {

    public VarcChangeEvent(int varId, int oldValue, int newValue) {
        this(varId, oldValue, newValue, System.currentTimeMillis());
    }
}
