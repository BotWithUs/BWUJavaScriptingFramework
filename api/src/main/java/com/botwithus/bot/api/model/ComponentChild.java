package com.botwithus.bot.api.model;

/**
 * One child of a component, with the sub id the game tells it apart by
 * ({@code get_static_children} / {@code get_dynamic_children} with {@code withSub: true}).
 *
 * <p>A dynamic child's {@code componentId} is its <b>parent's</b> id: every dynamic child of
 * one parent carries the same number, and only {@link #subId()} distinguishes them. That
 * sub id is the {@code param2} of a {@code COMPONENT} (57) or {@code SELECT_COMPONENT_ITEM}
 * (58) action on the child, as a backpack slot number is for the backpack grid.</p>
 *
 * @param componentId the child's component id ({@code -1} when the slot is unset)
 * @param subId       the child's sub id, {@code -1} when unset (static children)
 */
public record ComponentChild(int componentId, int subId) {

    /** The sub id the agent reports for a child that has none. */
    public static final int NO_SUB = -1;

    /** True when the child carries a sub id. */
    public boolean hasSub() {
        return subId != NO_SUB;
    }
}
