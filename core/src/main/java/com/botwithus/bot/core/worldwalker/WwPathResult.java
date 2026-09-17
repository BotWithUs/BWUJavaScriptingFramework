package com.botwithus.bot.core.worldwalker;

import java.util.List;

/**
 * An assembled path returned by {@link WorldWalker#query}. The native
 * {@code WwPath} buffer has already been decoded into a heap-resident
 * {@link List} and freed by the time this record reaches the caller, so the
 * record is safe to retain across queries.
 *
 * @param steps  the ordered Walk + Transition steps; empty when start and goal
 *               share an abstract node
 * @param cost   the planner's tick-units cost estimate
 */
public record WwPathResult(List<WwStep> steps, float cost) {

    public WwPathResult {
        steps = List.copyOf(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int stepCount() {
        return steps.size();
    }
}
