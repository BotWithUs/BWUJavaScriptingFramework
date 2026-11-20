package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.model.RouteLink;

import java.util.List;

/**
 * An assembled path returned by {@link WorldWalker#query}. The native
 * {@code WwPath} buffer has already been decoded into a heap-resident
 * {@link List} and freed by the time this record reaches the caller, so the
 * record is safe to retain across queries.
 *
 * @param steps         the ordered Walk + Transition steps; empty when start and goal
 *                      share an abstract node
 * @param cost          the planner's tick-units cost estimate
 * @param links         the transports and teleports the path takes ({@code ww_path_links});
 *                      empty when {@code linksResolved} is false
 * @param linksResolved false when the library lacks {@code ww_path_links} or it failed
 */
public record WwPathResult(List<WwStep> steps, float cost, List<RouteLink> links,
                           boolean linksResolved) {

    public WwPathResult {
        steps = List.copyOf(steps);
        links = List.copyOf(links);
    }

    /** A path with no links resolved. */
    public WwPathResult(List<WwStep> steps, float cost) {
        this(steps, cost, List.of(), false);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int stepCount() {
        return steps.size();
    }
}
