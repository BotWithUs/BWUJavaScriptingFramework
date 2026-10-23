package com.botwithus.bot.api.model;

import com.botwithus.bot.api.inventory.ActionTypes;

/**
 * A game action to be queued for execution via the pipe RPC.
 *
 * <p>Actions represent interactions such as clicking entities, using items, or
 * interacting with interface components. The meaning of the parameters depends on
 * the {@code actionId}.</p>
 *
 * @param actionId the action type ID (see {@link com.botwithus.bot.api.inventory.ActionTypes})
 * @param param1   first action parameter (often the option / op index)
 * @param param2   second action parameter (for a COMPONENT action, the slot /
 *                 sub-component id, or {@code -1} when there is none)
 * @param param3   third action parameter (for a COMPONENT action, the packed
 *                 {@code (iface<<16)|comp} component hash)
 * @see com.botwithus.bot.api.GameAPI#queueAction
 */
public record GameAction(int actionId, int param1, int param2, int param3) {

    /** Bits the low half of a packed wire parameter occupies. */
    private static final int HALF_BITS = 16;
    /** Mask for the low half of a packed wire parameter. */
    private static final int LOW_HALF = 0xFFFF;

    /**
     * A {@link ActionTypes#COMPONENT_TRIGGER} action: fire the CS2 event trigger of
     * type {@code triggerType} on a component. The packing is the agent's:
     * {@code param1 = (interfaceId << 16) | componentId},
     * {@code param2 = (triggerType << 16) | (subId & 0xFFFF)},
     * {@code param3 = arg}.
     *
     * @param subId sub-component id, or {@code -1} for the component itself
     * @param arg   type-dependent argument; for a key trigger the packed
     *              {@link com.botwithus.bot.api.input.KeyStroke#packed()}
     */
    public static GameAction componentTrigger(int interfaceId, int componentId, int subId,
                                              int triggerType, int arg) {
        int compHash = (interfaceId << HALF_BITS) | (componentId & LOW_HALF);
        int typeAndSub = (triggerType << HALF_BITS) | (subId & LOW_HALF);
        return new GameAction(ActionTypes.COMPONENT_TRIGGER, compHash, typeAndSub, arg);
    }
}
