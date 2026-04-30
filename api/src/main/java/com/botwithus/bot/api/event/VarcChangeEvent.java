package com.botwithus.bot.api.event;

/**
 * Fired when a client variable (varc) changes value. Distinct from
 * {@link VarChangeEvent} — varp and varc live in separate id namespaces and
 * separate VarDomain instances on the producer side, so subscribers must be
 * able to discriminate.
 */
public class VarcChangeEvent extends GameEvent {
    private final int varId;
    private final int oldValue;
    private final int newValue;

    public VarcChangeEvent(int varId, int oldValue, int newValue) {
        super("varc_change");
        this.varId = varId;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public int getVarId() { return varId; }
    public int getOldValue() { return oldValue; }
    public int getNewValue() { return newValue; }
}
