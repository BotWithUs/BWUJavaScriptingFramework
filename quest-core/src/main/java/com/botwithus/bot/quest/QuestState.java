package com.botwithus.bot.quest;

import java.util.Map;

/**
 * Immutable tuple of {@code varId -> value} for the tracker variables of one
 * quest. Modelled as a tuple rather than a single integer so quests that use
 * multiple progress vars (Goblin Diplomacy: varbits 297 + 298, Pirate's
 * Treasure: two varps) can dispatch unambiguously — a step's
 * {@code appliesTo} pattern-matches the full state, not just one value.
 *
 * <p>Constructed by {@link QuestProgressTracker}; passed into every
 * {@link QuestStep#appliesTo(QuestState)} call and exposed on
 * {@link QuestContext#state()} so step bodies can read sibling vars.</p>
 *
 * @param values immutable snapshot of the tracker values; unset ids resolve
 *               to {@code 0} via {@link #get(int)}
 */
public record QuestState(Map<Integer, Integer> values) {

    public QuestState {
        values = Map.copyOf(values);
    }

    /** Returns the cached value for {@code varId}, or {@code 0} if unset. */
    public int get(int varId) {
        return values.getOrDefault(varId, 0);
    }

    /** Equality match — {@code get(varId) == value}. */
    public boolean has(int varId, int value) {
        return get(varId) == value;
    }

    /** Inclusive range match — {@code lo <= get(varId) <= hi}. */
    public boolean inRange(int varId, int lo, int hi) {
        int v = get(varId);
        return v >= lo && v <= hi;
    }
}
