package com.botwithus.bot.api.model;

import java.util.List;

/**
 * Result of a pathfinding query.
 *
 * @param found         whether a path was found
 * @param pathLength    number of tiles in the path
 * @param path          ordered list of world-coordinate tiles from start to destination
 * @param links         the transports and teleports the route takes, in order; empty for a
 *                      pure walk, and empty when {@code linksResolved} is false
 * @param linksResolved false when the pathfinder library is too old to report links, so an
 *                      empty {@code links} says nothing about the route
 */
public record PathResult(
        boolean found,
        int pathLength,
        List<int[]> path,
        List<RouteLink> links,
        boolean linksResolved
) {

    public PathResult {
        links = List.copyOf(links);
    }

    /** The shape before links existed: links unresolved. */
    public PathResult(boolean found, int pathLength, List<int[]> path) {
        this(found, pathLength, path, List.of(), false);
    }
}
