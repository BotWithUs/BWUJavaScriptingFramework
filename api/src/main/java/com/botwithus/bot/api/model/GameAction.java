package com.botwithus.bot.api.model;

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
public record GameAction(int actionId, int param1, int param2, int param3) {}
