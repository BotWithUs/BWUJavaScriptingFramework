package com.botwithus.bot.api.model;

import java.util.Optional;

/**
 * The client's target mode ({@code get_target_mode}): an item selected with "Use", or a
 * spell or ability armed, waiting for the thing it is used on.
 *
 * <p><b>Predicate: {@link #active()}.</b> When it is false the rest is the agent's
 * "nothing selected" filler ({@code objId -1}, no source, empty strings) and means
 * nothing.</p>
 *
 * <p>Target mode survives queued actions that are not target actions: the client ends it
 * on its own input (right-click, Escape), on a session change, and, with an agent that
 * resets it, after a target action.</p>
 *
 * @param active     true while something is selected
 * @param isItem     true when an item is selected ({@code objId != -1})
 * @param objId      the selected item, {@code -1} for a spell or ability
 * @param source     the component the selection came from, if any
 * @param verb       the client's verb, e.g. "Use"
 * @param name       the selected thing's name, colour tags included
 * @param targetMask the client's mask of what the selection may be used on, raw
 */
public record TargetMode(boolean active, boolean isItem, int objId, Optional<Source> source,
                         String verb, String name, int targetMask) {

    /** What the agent reports when nothing is selected. */
    public static final TargetMode INACTIVE =
            new TargetMode(false, false, -1, Optional.empty(), "", "", 0);

    public TargetMode {
        if (source == null) {
            throw new IllegalArgumentException("source");
        }
    }

    /** True when {@code itemId} is the selected item. */
    public boolean isItemSelected(int itemId) {
        return active && isItem && objId == itemId;
    }

    /**
     * True when {@code itemId} is selected <b>from</b> {@code (interfaceId, componentId,
     * subId)}. This, not {@link #active()} or {@link #isItemSelected(int)}, is what says a
     * particular arm has landed: a client already armed from another slot reads active, and
     * with the same item, before the new arm is dispatched.
     */
    public boolean isSelectedFrom(int itemId, int interfaceId, int componentId, int subId) {
        return isItemSelected(itemId)
                && source.equals(Optional.of(new Source(interfaceId, componentId, subId)));
    }

    /**
     * The component a selection came from.
     *
     * @param interfaceId the interface
     * @param componentId the component
     * @param subId       the dynamic-child index (the backpack slot), {@code -1} if none
     */
    public record Source(int interfaceId, int componentId, int subId) {
    }
}
