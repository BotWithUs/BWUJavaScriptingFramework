package com.botwithus.bot.api.model;

import java.util.List;

/**
 * Result of a pathfinding query ({@code find_path} or {@code find_world_path}).
 *
 * @param found        whether a path was found
 * @param pathLength   number of tiles in the path
 * @param path         ordered list of world-coordinate tiles from start to destination
 */
public record PathResult(
        boolean found,
        int pathLength,
        List<int[]> path
) {}
