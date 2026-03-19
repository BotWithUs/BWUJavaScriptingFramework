package com.botwithus.bot.api.model;

/**
 * Configuration for world-scale pathfinding via {@code walk_world_path}.
 * All fields use defaults matching the server; only override what you need.
 *
 * <p>Use the {@link Builder} for convenient construction:</p>
 * <pre>{@code
 * var config = WorldPathConfig.builder()
 *         .allowTeleports(false)
 *         .allowShortcuts(true)
 *         .build();
 * }</pre>
 *
 * @param agilityLevel              player agility level (≤1 = auto-read from stats)
 * @param maxIterations             max pathfinder iterations
 * @param allowDoors                allow opening doors
 * @param allowShortcuts            allow agility shortcuts
 * @param allowPlaneTransitions     allow stairs/ladders
 * @param allowClimbovers           allow stiles/climbable obstacles
 * @param allowTransports           allow transport links (boats, carts, etc.)
 * @param allowTeleports            allow teleport spells/items
 * @param doorCost                  extra cost for door traversal
 * @param transitionCost            extra cost for plane transitions
 * @param shortcutCost              extra cost for agility shortcuts
 * @param climboverCost             extra cost for climbovers
 * @param transportCost             extra cost for transport links
 * @param globalTeleportMinHeuristic min heuristic distance before teleports considered
 * @param heuristicWeight           A* heuristic weight multiplier
 */
public record WorldPathConfig(
        int agilityLevel,
        int maxIterations,
        boolean allowDoors,
        boolean allowShortcuts,
        boolean allowPlaneTransitions,
        boolean allowClimbovers,
        boolean allowTransports,
        boolean allowTeleports,
        float doorCost,
        float transitionCost,
        float shortcutCost,
        float climboverCost,
        float transportCost,
        float globalTeleportMinHeuristic,
        float heuristicWeight
) {

    /** Default config matching server defaults. */
    public static final WorldPathConfig DEFAULT = new WorldPathConfig(
            0, 500_000,
            true, true, true, true, true, true,
            5.0f, 10.0f, 3.0f, 3.0f, 15.0f,
            100.0f, 1.0f
    );

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int agilityLevel = 0;
        private int maxIterations = 500_000;
        private boolean allowDoors = true;
        private boolean allowShortcuts = true;
        private boolean allowPlaneTransitions = true;
        private boolean allowClimbovers = true;
        private boolean allowTransports = true;
        private boolean allowTeleports = true;
        private float doorCost = 5.0f;
        private float transitionCost = 10.0f;
        private float shortcutCost = 3.0f;
        private float climboverCost = 3.0f;
        private float transportCost = 15.0f;
        private float globalTeleportMinHeuristic = 100.0f;
        private float heuristicWeight = 1.0f;

        private Builder() {}

        public Builder agilityLevel(int val) { agilityLevel = val; return this; }
        public Builder maxIterations(int val) { maxIterations = val; return this; }
        public Builder allowDoors(boolean val) { allowDoors = val; return this; }
        public Builder allowShortcuts(boolean val) { allowShortcuts = val; return this; }
        public Builder allowPlaneTransitions(boolean val) { allowPlaneTransitions = val; return this; }
        public Builder allowClimbovers(boolean val) { allowClimbovers = val; return this; }
        public Builder allowTransports(boolean val) { allowTransports = val; return this; }
        public Builder allowTeleports(boolean val) { allowTeleports = val; return this; }
        public Builder doorCost(float val) { doorCost = val; return this; }
        public Builder transitionCost(float val) { transitionCost = val; return this; }
        public Builder shortcutCost(float val) { shortcutCost = val; return this; }
        public Builder climboverCost(float val) { climboverCost = val; return this; }
        public Builder transportCost(float val) { transportCost = val; return this; }
        public Builder globalTeleportMinHeuristic(float val) { globalTeleportMinHeuristic = val; return this; }
        public Builder heuristicWeight(float val) { heuristicWeight = val; return this; }

        public WorldPathConfig build() {
            return new WorldPathConfig(
                    agilityLevel, maxIterations,
                    allowDoors, allowShortcuts, allowPlaneTransitions,
                    allowClimbovers, allowTransports, allowTeleports,
                    doorCost, transitionCost, shortcutCost, climboverCost,
                    transportCost, globalTeleportMinHeuristic, heuristicWeight
            );
        }
    }
}
