package com.botwithus.bot.skilling.script;

/**
 * How a {@link GatherScript} disposes of a full backpack.
 *
 * <ul>
 *   <li>{@link #BANK} — walk to the nearest Atlas bank, open it, deposit the
 *       carried resources, return. The default.</li>
 *   <li>{@link #DROP} — drop the gathered item (power-gather); never banks.</li>
 *   <li>{@link #BOX} — fill a {@link com.botwithus.bot.skilling.inventory.ResourceBox
 *       resource box} (wood box / ore box) to extend the trip; only walk to a bank
 *       once the box itself is full, emptying it there before depositing.</li>
 * </ul>
 */
public enum Disposal {
    BANK,
    DROP,
    BOX
}
