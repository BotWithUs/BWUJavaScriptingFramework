package com.botwithus.bot.quest;

import java.util.List;

/**
 * Result of pre-flight requirement validation against a player's current
 * skills, quest points, and completed-quest record. Built by
 * {@link QuestRequirements#check}; consumed by {@code QuestScript.onStart}
 * to gate execution and by the router on every step transition to catch
 * mid-script regressions (drained skill, lapsed membership).
 *
 * @param ok      {@code true} when {@link #missing()} is empty
 * @param missing every unmet requirement; ordered for stable annotation output
 */
public record RequirementCheck(boolean ok, List<Missing> missing) {

    public RequirementCheck {
        missing = List.copyOf(missing);
    }

    /** Convenience constant for the "no requirements unmet" case. */
    public static RequirementCheck pass() {
        return new RequirementCheck(true, List.of());
    }

    /** Builds a failing check from the unmet-requirement list. */
    public static RequirementCheck fail(List<Missing> missing) {
        return new RequirementCheck(false, missing);
    }

    /** One unmet requirement. Sealed so callers can pattern-match exhaustively. */
    public sealed interface Missing {

        /** Skill level requirement not met. */
        record Skill(int skillId, int required, int actual) implements Missing {}

        /** Quest-point total below the required threshold. */
        record QuestPoints(int required, int actual) implements Missing {}

        /** Prerequisite quest not completed. */
        record DependentQuest(int questId) implements Missing {}

        /** Members-only quest attempted on an F2P account. */
        record Membership() implements Missing {}
    }
}
