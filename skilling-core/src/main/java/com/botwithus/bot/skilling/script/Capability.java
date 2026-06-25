package com.botwithus.bot.skilling.script;

import com.botwithus.bot.api.ScriptCategory;

import java.util.Set;

/**
 * What a skill script can do, so the future cross-skill {@code Orchestrator} can
 * route a production goal's raw materials to the right gatherer (and craft steps
 * to the right maker). A gather script declares the item ids it can produce by
 * gathering; a production script declares the item ids it can craft.
 *
 * @param skill        the script's skill
 * @param gatherItems  item ids this script can obtain by gathering (mine/chop/fish)
 * @param produceItems item ids this script can craft/smith/cook
 */
public record Capability(ScriptCategory skill, Set<Integer> gatherItems, Set<Integer> produceItems) {

    public Capability {
        gatherItems = Set.copyOf(gatherItems);
        produceItems = Set.copyOf(produceItems);
    }

    /** A gather-only capability (e.g. Woodcutting produces logs by chopping). */
    public static Capability gather(ScriptCategory skill, int... items) {
        return new Capability(skill, toSet(items), Set.of());
    }

    /** A production-only capability (e.g. Smithing makes bars/equipment). */
    public static Capability produce(ScriptCategory skill, int... items) {
        return new Capability(skill, Set.of(), toSet(items));
    }

    public boolean canGather(int itemId) {
        return gatherItems.contains(itemId);
    }

    public boolean canProduce(int itemId) {
        return produceItems.contains(itemId);
    }

    private static Set<Integer> toSet(int[] items) {
        Set<Integer> s = new java.util.HashSet<>(items.length);
        for (int i : items) {
            s.add(i);
        }
        return s;
    }
}
