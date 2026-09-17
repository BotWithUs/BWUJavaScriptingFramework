package com.botwithus.bot.api.event;

/**
 * Fired when a radio-group option is selected, observed on the producer's
 * outbound {@code SendRadioButton} packet builder. Covers both manual (player)
 * selections and bot-driven ones queued as a {@code RadioGroupSelect} action.
 *
 * <p>A radio select does not ride the normal MiniMenu action packet — the
 * producer hooks the dedicated packet builder and republishes each selection
 * here. {@code interfaceId}/{@code componentId} identify the radio-group
 * component; {@code subId} is the sub-component id ({@code -1} when none);
 * {@code value} is a component-derived secondary id ({@code -1} in practice for
 * a plain select); {@code opcode} is the resolved ClientProt id, which encodes
 * which option index was chosen.</p>
 *
 * @param interfaceId the owning interface id
 * @param componentId the component id within the interface
 * @param subId       the sub-component id, or {@code -1} when none
 * @param value       component-derived secondary id, or {@code -1} when none
 * @param opcode      the outbound ClientProt opcode for the chosen option
 * @param timestamp   event creation time in milliseconds since epoch
 */
public record RadioGroupSelectEvent(int interfaceId, int componentId, int subId,
                                    int value, int opcode, long timestamp)
        implements GameEvent {

    public RadioGroupSelectEvent(int interfaceId, int componentId, int subId, int value, int opcode) {
        this(interfaceId, componentId, subId, value, opcode, System.currentTimeMillis());
    }
}
