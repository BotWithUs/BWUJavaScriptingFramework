package com.botwithus.bot.api.event;

/**
 * Fired when a player variable (varp) changes value.
 *
 * @param varId     the varp ID that changed
 * @param oldValue  the previous value
 * @param newValue  the new value
 * @param timestamp event creation time in milliseconds since epoch
 */
public record VarChangeEvent(int varId, int oldValue, int newValue, long timestamp) implements GameEvent {

    public VarChangeEvent(int varId, int oldValue, int newValue) {
        this(varId, oldValue, newValue, System.currentTimeMillis());
    }
}
