package com.botwithus.bot.skilling.plan;

import com.botwithus.bot.skilling.atlas.Atlas;
import com.botwithus.bot.skilling.atlas.BuildPlan;

import java.util.Set;

/**
 * Turns a {@link Goal.Production} into a {@link BuildPlan} by expanding the Atlas
 * recipe closure — the "make 100 bronze bars → need 100 copper + 100 tin ore"
 * decomposition. A thin wrapper over {@link Atlas#closure}; the heavy lifting
 * (DFS, topological order, quantity rollup, raw materials with gather spots) is in
 * the Atlas so the same logic serves the web analyzer and the host.
 */
public final class Planner {

    private final Atlas atlas;

    public Planner(Atlas atlas) {
        this.atlas = atlas;
    }

    /** Expand a production goal into its full gather/craft dependency plan. */
    public BuildPlan plan(Goal.Production goal) {
        return plan(goal, Set.of());
    }

    /**
     * As {@link #plan(Goal.Production)}, but treat the named skills' outputs as
     * leaves (gather/buy, don't craft) — used for conversion skills (Divination
     * transmutes, Invention manufacture) so their inputs plan as gathered.
     */
    public BuildPlan plan(Goal.Production goal, Set<String> stopSkills) {
        return atlas.closure(goal.itemId(), goal.quantity(), stopSkills);
    }
}
