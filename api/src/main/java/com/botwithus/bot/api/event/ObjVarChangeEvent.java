package com.botwithus.bot.api.event;

/**
 * Fired when a per-item obj var changes value.
 *
 * <p>Obj vars are the per-instance variables an item carries inside a
 * container slot's {@code ObjVarDomain} (augmentation XP, charges, etc.) —
 * distinct from the global player varp / client varc domains. The value space
 * is keyed by {@code (invId, slot, varId)}; correlate {@code slot} with the
 * snapshot's inventory items to find which item it belongs to.</p>
 *
 * @param invId     the container id (e.g. 93 = backpack)
 * @param slot      the 0-based slot index within the container
 * @param varId     the obj-var id that changed
 * @param oldValue  the previous value
 * @param newValue  the new value
 * @param timestamp event creation time in milliseconds since epoch
 */
public record ObjVarChangeEvent(int invId, int slot, int varId, int oldValue, int newValue,
                                long timestamp) implements GameEvent {

    public ObjVarChangeEvent(int invId, int slot, int varId, int oldValue, int newValue) {
        this(invId, slot, varId, oldValue, newValue, System.currentTimeMillis());
    }
}
