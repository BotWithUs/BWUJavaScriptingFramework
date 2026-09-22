package com.botwithus.bot.quest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-time identity for a quest: the cache id, the wiki display name,
 * and the variables that track its progress.
 *
 * <p>Instances are produced by the build-time {@link Quests} codegen — one
 * {@code public static final} field per F2P quest in
 * {@code merged_quests.json}. Everything else (skill requirements,
 * dependent quests, start tile, QP reward) resolves at runtime through
 * {@link com.botwithus.bot.api.GameAPI#getQuestType(int)} so it tracks
 * cache changes; the codegen only freezes the identity tuple here.</p>
 *
 * <p>Each tracker carries its {@link TrackerVar.Kind kind}, because a varp and a
 * varbit with the same number are different variables. {@link QuestState} is
 * keyed by the bare number, which is unambiguous only while no quest tracks a
 * varp and a varbit with the same number, so the constructor refuses that.</p>
 *
 * @param id       the quest type id, matching
 *                 {@link com.botwithus.bot.api.model.QuestType#id()}
 * @param name     the wiki display name
 * @param trackers the variables whose tuple of values uniquely identifies the
 *                 current progress state; preferred source is
 *                 {@code action_chains_sample.json::tracker} when present,
 *                 else the first entry of each {@code progressVarbits} /
 *                 {@code progressVarps} row
 */
public record QuestId(int id, String name, List<TrackerVar> trackers) {

    public QuestId {
        Objects.requireNonNull(name, "name");
        trackers = List.copyOf(trackers);
        requireDistinctNumbers(trackers);
    }

    /**
     * Every tracked id, whatever its kind, in declaration order.
     *
     * @deprecated the bare numbers lose which id space each belongs to; varp 297 and
     *             varbit 297 are different variables. Use {@link #trackers()},
     *             {@link #trackerVarps()} or {@link #trackerVarbits()}.
     */
    @Deprecated
    public int[] trackerVars() {
        return trackers.stream().mapToInt(TrackerVar::id).toArray();
    }

    /** The tracked ids that are varps. */
    public int[] trackerVarps() {
        return idsOf(TrackerVar.Kind.VARP);
    }

    /** The tracked ids that are varbits. */
    public int[] trackerVarbits() {
        return idsOf(TrackerVar.Kind.VARBIT);
    }

    private int[] idsOf(TrackerVar.Kind kind) {
        return trackers.stream().filter(t -> t.kind() == kind).mapToInt(TrackerVar::id).toArray();
    }

    private static void requireDistinctNumbers(List<TrackerVar> trackers) {
        Set<Integer> seen = new HashSet<>();
        for (TrackerVar t : trackers) {
            if (!seen.add(t.id())) {
                throw new IllegalArgumentException("tracker id " + t.id()
                        + " appears twice; QuestState keys by number, so a quest cannot"
                        + " track a varp and a varbit with the same id");
            }
        }
    }
}
