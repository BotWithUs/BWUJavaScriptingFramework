package com.botwithus.bot.api.model;

import com.botwithus.bot.api.world.Area;
import com.botwithus.bot.api.world.RectangularArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for world-scale pathfinding. All fields use defaults matching
 * the pathfinder's own; only override what you need.
 *
 * <p>Use the {@link Builder} for convenient construction:</p>
 * <pre>{@code
 * var config = WorldPathConfig.builder()
 *         .allowTeleports(false)
 *         .allowShortcuts(true)
 *         .allowSurge(false)
 *         .build();
 * }</pre>
 *
 * <p>What the world walker honours: the {@code allow*} flags, here and on
 * {@link #movement()}. It routes against a pre-baked graph, so
 * {@code agilityLevel}, {@code maxIterations}, the five {@code *Cost} fields,
 * {@code globalTeleportMinHeuristic} and {@code heuristicWeight} are carried
 * but not applied. A walker build that predates movement options honours none
 * of the {@code allow*} flags except {@link MovementOptions#allowSurge()} and
 * {@link MovementOptions#allowDive()}, which the host applies itself; the host
 * logs a warning when that happens.</p>
 *
 * <h2>Avoid areas</h2>
 * {@link #avoidAreas()} are tile rectangles the walker never routes through, on the first
 * plan and every re-plan: no walk step onto one of their tiles, and no door, stairs, boat,
 * shortcut or teleport that starts or lands inside one. A ride that only passes over one on
 * its way (a boat crossing it) is not refused, because only the two ends of a transport are
 * known. An area holding the walk's start or goal tile is not applied to that plan, or the
 * walk could never leave or reach it. A route that would have to cross an area fails with
 * no route; it is never quietly taken through it. At most {@link #MAX_AVOID_AREAS}.
 *
 * <p>{@link #avoid(Area...)} takes any {@link Area}. A {@link RectangularArea} is sent
 * exactly. A circle or a polygon is <b>covered by its bounding box</b>, so the walker also
 * avoids the tiles in the box's corners outside the shape: conservative, never through the
 * shape. An {@code Area} with no {@link Area#boundingBox()} is refused. A
 * {@code worldwalker.dll} too old for avoid areas makes a walk or query that carries any
 * fail rather than route through them.</p>
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
 * @param movement                  the special movement the walker may use; never {@code null}
 * @param avoidAreas                rectangles the walker never routes through; never {@code null}
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
        float heuristicWeight,
        MovementOptions movement,
        List<RectangularArea> avoidAreas
) {

    /** Most avoid areas one config may carry ({@code WW_MAX_AVOID_AREAS}). */
    public static final int MAX_AVOID_AREAS = 64;

    // ── Defaults matching the server. Named once and reused by both DEFAULT and
    //    the Builder field initializers so a bump on one side can't drift away from the other.
    private static final int   DEFAULT_AGILITY_LEVEL_AUTO       = 0;
    private static final int   DEFAULT_MAX_ITERATIONS           = 500_000;
    private static final float DEFAULT_DOOR_COST                = 5.0f;
    private static final float DEFAULT_TRANSITION_COST          = 10.0f;
    private static final float DEFAULT_SHORTCUT_COST            = 3.0f;
    private static final float DEFAULT_CLIMBOVER_COST           = 3.0f;
    private static final float DEFAULT_TRANSPORT_COST           = 15.0f;
    private static final float DEFAULT_TELEPORT_MIN_HEURISTIC   = 100.0f;
    private static final float DEFAULT_HEURISTIC_WEIGHT         = 1.0f;

    /** Default config matching server defaults, with every movement option allowed. */
    public static final WorldPathConfig DEFAULT = new WorldPathConfig(
            DEFAULT_AGILITY_LEVEL_AUTO, DEFAULT_MAX_ITERATIONS,
            true, true, true, true, true, true,
            DEFAULT_DOOR_COST, DEFAULT_TRANSITION_COST, DEFAULT_SHORTCUT_COST,
            DEFAULT_CLIMBOVER_COST, DEFAULT_TRANSPORT_COST,
            DEFAULT_TELEPORT_MIN_HEURISTIC, DEFAULT_HEURISTIC_WEIGHT
    );

    public WorldPathConfig {
        Objects.requireNonNull(movement, "movement");
        avoidAreas = List.copyOf(avoidAreas);
        if (avoidAreas.size() > MAX_AVOID_AREAS) {
            throw new IllegalArgumentException("at most " + MAX_AVOID_AREAS
                    + " avoid areas, got " + avoidAreas.size());
        }
    }

    /** The constructor as it was before {@link #avoidAreas()} existed: nothing avoided. */
    public WorldPathConfig(int agilityLevel, int maxIterations,
                           boolean allowDoors, boolean allowShortcuts,
                           boolean allowPlaneTransitions, boolean allowClimbovers,
                           boolean allowTransports, boolean allowTeleports,
                           float doorCost, float transitionCost, float shortcutCost,
                           float climboverCost, float transportCost,
                           float globalTeleportMinHeuristic, float heuristicWeight,
                           MovementOptions movement) {
        this(agilityLevel, maxIterations, allowDoors, allowShortcuts, allowPlaneTransitions,
                allowClimbovers, allowTransports, allowTeleports, doorCost, transitionCost,
                shortcutCost, climboverCost, transportCost, globalTeleportMinHeuristic,
                heuristicWeight, movement, List.of());
    }

    /**
     * This config with {@code areas} avoided as well as any already avoided. See the class
     * comment for how each shape becomes a rectangle.
     *
     * @throws IllegalArgumentException for an area with no bounding box, or past
     *                                  {@link #MAX_AVOID_AREAS} in all
     */
    public WorldPathConfig avoid(Area... areas) {
        List<RectangularArea> all = new ArrayList<>(avoidAreas);
        all.addAll(toRectangles(areas));
        return new WorldPathConfig(agilityLevel, maxIterations, allowDoors, allowShortcuts,
                allowPlaneTransitions, allowClimbovers, allowTransports, allowTeleports,
                doorCost, transitionCost, shortcutCost, climboverCost, transportCost,
                globalTeleportMinHeuristic, heuristicWeight, movement, all);
    }

    /** Each area's rectangle: itself, or its bounding box. */
    static List<RectangularArea> toRectangles(Area... areas) {
        List<RectangularArea> out = new ArrayList<>(areas.length);
        for (Area area : areas) {
            Objects.requireNonNull(area, "area");
            out.add(area.boundingBox().orElseThrow(() -> new IllegalArgumentException(
                    "avoid area " + area + " has no bounding box; pass a RectangularArea")));
        }
        return out;
    }

    /**
     * The constructor as it was before {@link #movement()} existed: every movement option
     * allowed.
     */
    public WorldPathConfig(int agilityLevel, int maxIterations,
                           boolean allowDoors, boolean allowShortcuts,
                           boolean allowPlaneTransitions, boolean allowClimbovers,
                           boolean allowTransports, boolean allowTeleports,
                           float doorCost, float transitionCost, float shortcutCost,
                           float climboverCost, float transportCost,
                           float globalTeleportMinHeuristic, float heuristicWeight) {
        this(agilityLevel, maxIterations, allowDoors, allowShortcuts, allowPlaneTransitions,
                allowClimbovers, allowTransports, allowTeleports, doorCost, transitionCost,
                shortcutCost, climboverCost, transportCost, globalTeleportMinHeuristic,
                heuristicWeight, MovementOptions.ALL);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Special movement the walker may use, each allowed by default. The first seven narrow
     * the transitions the planner routes through; they apply on top of
     * {@link #allowTeleports()} and {@link #allowTransports()}, so turning a whole class off
     * there turns these off with it. {@code allowSurge} and {@code allowDive} gate the
     * movement abilities the host fires on long walk hops.
     *
     * @param allowSpiritTrees          allow spirit-tree travel
     * @param allowGliders              allow gnome-glider travel
     * @param allowCharters             allow charter ships
     * @param allowFairyRings           allow fairy rings
     * @param allowLodestones           allow lodestone teleports
     * @param allowMagicCarpets         allow magic-carpet travel
     * @param allowOtherTeleportChains  allow every other teleport chain (items, spells, ...)
     * @param allowSurge                allow the Surge ability on walk hops
     * @param allowDive                 allow the Dive / Bladed Dive ability on walk hops
     */
    public record MovementOptions(
            boolean allowSpiritTrees,
            boolean allowGliders,
            boolean allowCharters,
            boolean allowFairyRings,
            boolean allowLodestones,
            boolean allowMagicCarpets,
            boolean allowOtherTeleportChains,
            boolean allowSurge,
            boolean allowDive
    ) {
        /** Every movement option allowed. */
        public static final MovementOptions ALL =
                new MovementOptions(true, true, true, true, true, true, true, true, true);
    }

    public static final class Builder {
        private int agilityLevel = DEFAULT_AGILITY_LEVEL_AUTO;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private boolean allowDoors = true;
        private boolean allowShortcuts = true;
        private boolean allowPlaneTransitions = true;
        private boolean allowClimbovers = true;
        private boolean allowTransports = true;
        private boolean allowTeleports = true;
        private float doorCost = DEFAULT_DOOR_COST;
        private float transitionCost = DEFAULT_TRANSITION_COST;
        private float shortcutCost = DEFAULT_SHORTCUT_COST;
        private float climboverCost = DEFAULT_CLIMBOVER_COST;
        private float transportCost = DEFAULT_TRANSPORT_COST;
        private float globalTeleportMinHeuristic = DEFAULT_TELEPORT_MIN_HEURISTIC;
        private float heuristicWeight = DEFAULT_HEURISTIC_WEIGHT;
        private boolean allowSpiritTrees = true;
        private boolean allowGliders = true;
        private boolean allowCharters = true;
        private boolean allowFairyRings = true;
        private boolean allowLodestones = true;
        private boolean allowMagicCarpets = true;
        private boolean allowOtherTeleportChains = true;
        private boolean allowSurge = true;
        private boolean allowDive = true;
        private final List<RectangularArea> avoidAreas = new ArrayList<>();

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
        public Builder allowSpiritTrees(boolean val) { allowSpiritTrees = val; return this; }
        public Builder allowGliders(boolean val) { allowGliders = val; return this; }
        public Builder allowCharters(boolean val) { allowCharters = val; return this; }
        public Builder allowFairyRings(boolean val) { allowFairyRings = val; return this; }
        public Builder allowLodestones(boolean val) { allowLodestones = val; return this; }
        public Builder allowMagicCarpets(boolean val) { allowMagicCarpets = val; return this; }
        public Builder allowOtherTeleportChains(boolean val) { allowOtherTeleportChains = val; return this; }
        public Builder allowSurge(boolean val) { allowSurge = val; return this; }
        public Builder allowDive(boolean val) { allowDive = val; return this; }

        /** Avoids {@code areas} as well; see {@link WorldPathConfig#avoid(Area...)}. */
        public Builder avoid(Area... areas) {
            avoidAreas.addAll(toRectangles(areas));
            return this;
        }

        public WorldPathConfig build() {
            return new WorldPathConfig(
                    agilityLevel, maxIterations,
                    allowDoors, allowShortcuts, allowPlaneTransitions,
                    allowClimbovers, allowTransports, allowTeleports,
                    doorCost, transitionCost, shortcutCost, climboverCost,
                    transportCost, globalTeleportMinHeuristic, heuristicWeight,
                    new MovementOptions(allowSpiritTrees, allowGliders, allowCharters,
                            allowFairyRings, allowLodestones, allowMagicCarpets,
                            allowOtherTeleportChains, allowSurge, allowDive),
                    avoidAreas
            );
        }
    }
}
