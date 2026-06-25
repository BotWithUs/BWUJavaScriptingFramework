package com.botwithus.bot.skilling.atlas;

import java.util.List;
import java.util.Map;

/**
 * The transitive recipe dependency closure of a production goal — the host port
 * of the analyzer's {@code _compute_closure}. Answers "to make N of item X, what
 * raw materials do I need (and where do I gather them), in what build order, for
 * how much xp per skill".
 *
 * <p>A node is {@code raw} (a leaf) when it has no recipe, its recipe is a
 * gatherable resource, or its recipe skill was named in {@code stopSkills} — i.e.
 * something to gather/buy rather than craft. {@code rawMaterials} are exactly the
 * raw leaves with their rolled-up required quantities and gather spots; that list
 * is what the cross-skill orchestrator dispatches gather scripts for.</p>
 *
 * @param targetItem    the goal item id
 * @param targetQty     how many of it the plan was rolled up for
 * @param nodes         every item in the closure (product + intermediates + raws)
 * @param topoOrder     item ids in product-before-ingredient order (build order)
 * @param rawMaterials  the raw leaves to acquire, sorted by descending quantity
 * @param xpBySkill     total xp earned per skill executing the whole plan
 * @param hasCycles     whether a recipe dependency cycle was detected
 * @param truncated     whether the walk hit the node cap before completing
 */
public record BuildPlan(int targetItem, double targetQty,
                        List<Node> nodes, List<Integer> topoOrder,
                        List<RawMaterial> rawMaterials,
                        Map<String, Double> xpBySkill,
                        boolean hasCycles, boolean truncated) {

    /**
     * One item in the closure with its rolled-up required quantity.
     *
     * @param item         item id
     * @param label        display name (gameval or item name)
     * @param hasRecipe    whether the item has a production recipe
     * @param gatherable   whether the item appears in the gather table
     * @param raw          whether this is a leaf (gather/buy, don't craft)
     * @param skill        production skill (if any)
     * @param level        first skill-level requirement (if any)
     * @param xp           xp per craft (if any)
     * @param makeQuantity units produced per craft
     * @param requiredQty  total quantity of this item the plan needs
     */
    public record Node(int item, String label, boolean hasRecipe, boolean gatherable,
                       boolean raw, String skill, Integer level, Double xp,
                       int makeQuantity, double requiredQty) {}

    /** A raw material to acquire: {@code qty} of {@code item}, gatherable at {@code gatherSpots}. */
    public record RawMaterial(int item, String label, double qty, List<Spot> gatherSpots) {}
}
