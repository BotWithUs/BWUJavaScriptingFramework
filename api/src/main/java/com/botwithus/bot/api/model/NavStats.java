package com.botwithus.bot.api.model;

/**
 * Navigation data statistics from the pathfinder.
 *
 * @param regions          number of loaded regions
 * @param doors            number of door overrides
 * @param shortcuts        number of shortcut overrides
 * @param planeTransitions number of plane transition overrides
 * @param climbovers       number of climbover overrides
 * @param transports       number of transport link overrides
 * @param teleports        total registered teleports
 * @param teleportsBuiltin built-in (embedded) teleports
 * @param teleportsScript  script-registered teleports
 */
public record NavStats(
        int regions,
        int doors,
        int shortcuts,
        int planeTransitions,
        int climbovers,
        int transports,
        int teleports,
        int teleportsBuiltin,
        int teleportsScript
) {}
