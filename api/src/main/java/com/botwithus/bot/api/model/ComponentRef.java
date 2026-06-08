package com.botwithus.bot.api.model;

/**
 * Identifies a component by its owning interface and component id within that
 * interface. Input shape for batched component lookups
 * ({@link com.botwithus.bot.api.GameAPI#getComponents}).
 */
public record ComponentRef(int interfaceId, int componentId) {
}
