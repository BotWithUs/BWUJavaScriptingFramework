package com.botwithus.bot.api.event;

/**
 * Fired when a varbit changes value.
 *
 * @param varId     the varbit ID that changed
 * @param oldValue  the previous value
 * @param newValue  the new value
 * @param timestamp event creation time in milliseconds since epoch
 */
public record VarbitChangeEvent(int varId, int oldValue, int newValue, long timestamp) implements GameEvent {

    public VarbitChangeEvent(int varId, int oldValue, int newValue) {
        this(varId, oldValue, newValue, System.currentTimeMillis());
    }
}
