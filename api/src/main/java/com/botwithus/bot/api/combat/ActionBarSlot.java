package com.botwithus.bot.api.combat;

/**
 * What one slot of an action bar holds. Dispatch with a {@code switch}; the variants are
 * closed.
 */
public sealed interface ActionBarSlot {

    /** The slot number, {@code 1..ActionBar.SLOTS}. */
    int slot();

    /** An empty slot. */
    record Empty(int slot) implements ActionBarSlot {}

    /** A slot holding an ability (or prayer, or other cache-defined action). */
    record AbilitySlot(int slot, Ability ability) implements ActionBarSlot {}

    /** A slot holding an item. */
    record ItemSlot(int slot, int itemId) implements ActionBarSlot {}

    /**
     * A slot whose contents the cache could not name: a type code with no ability enum,
     * an id missing from it, or no cache available. The raw values are kept so a script
     * can still tell two slots apart.
     */
    record Unresolved(int slot, int typeCode, int id) implements ActionBarSlot {}
}
