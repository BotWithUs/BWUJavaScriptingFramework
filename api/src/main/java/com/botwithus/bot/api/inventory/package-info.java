/**
 * Inventory facades and the constants needed to build raw component
 * actions. Snapshot-backed reads (via {@link com.botwithus.bot.api.GameAPI#snapshot})
 * with cached {@link com.botwithus.bot.api.model.ItemType} lookups for
 * name resolution.
 *
 * <p>Standard usage:</p>
 * <pre>{@code
 *   if (api.backpack().isFull()) { ... }
 *   api.backpack().interactFirst(LOG_ID, "Drop");
 *   if (api.bank().contains("Coins", 1000)) { ... }
 * }</pre>
 *
 * <p>The facades ({@link com.botwithus.bot.api.inventory.Backpack},
 * {@link com.botwithus.bot.api.inventory.Bank},
 * {@link com.botwithus.bot.api.inventory.Equipment}) are singletons per
 * {@link com.botwithus.bot.api.GameAPI}; only the per-call result lists
 * allocate.</p>
 */
package com.botwithus.bot.api.inventory;
