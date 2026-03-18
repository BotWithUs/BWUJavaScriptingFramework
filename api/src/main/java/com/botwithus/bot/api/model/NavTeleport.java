package com.botwithus.bot.api.model;

/**
 * A registered teleport in the pathfinder.
 *
 * @param index       teleport index
 * @param name        teleport display name
 * @param global      whether the teleport is available from any location
 * @param destX       destination world tile X
 * @param destY       destination world tile Y
 * @param destPlane   destination plane
 * @param cost        pathfinding cost weight
 * @param costQuick   quick-teleport cost ({@code -1} if not available)
 * @param chainSteps  number of steps in the action chain
 * @param requirements number of requirements
 * @param builtin     whether this is a built-in (not script-registered) teleport
 */
public record NavTeleport(
        int index,
        String name,
        boolean global,
        int destX,
        int destY,
        int destPlane,
        double cost,
        double costQuick,
        int chainSteps,
        int requirements,
        boolean builtin
) {}
