package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * One unit of quest progression. Steps are declared by {@code QuestScript}
 * subclasses through the {@link Steps} DSL and consumed by the router task,
 * which on each loop:
 *
 * <ol>
 *   <li>refreshes the {@link QuestState} from {@code QuestProgressTracker};</li>
 *   <li>picks the highest-priority step whose
 *       {@link #appliesTo(QuestState, GameAPI)} returns {@code true};</li>
 *   <li>calls {@link #onEnter(QuestContext)} once when a step transitions to
 *       active (so steps with mutable state can reset);</li>
 *   <li>calls {@link #execute(QuestContext)}, then polls {@link #success()}
 *       until it returns {@code true} or the {@link #timeout()} budget is
 *       exhausted.</li>
 * </ol>
 *
 * <p>The per-step success predicate is what makes the engine safe against
 * steps that don't advance a tracker varbit — e.g. a walk terminates on
 * arrival, a pickup terminates on the item appearing in the backpack. A
 * varbit-gated step uses {@link Steps#waitForVar} explicitly.</p>
 *
 * <p>{@code appliesTo} sees the live {@link GameAPI} too — gates like
 * "this sequence is only active when the backpack already has the three
 * ingredients" need to read state outside the tracker tuple.</p>
 */
public interface QuestStep {

    /** Short identifier used in {@code quest.current_step} annotations / traces. */
    String name();

    /**
     * Predicate over the tracker tuple plus live game state: {@code true}
     * means "this is the active step for the current progression".
     */
    boolean appliesTo(QuestState state, GameAPI api);

    /**
     * Called once by the router when this step transitions from inactive to
     * active. Implementations with mutable state (click pointers, etc.)
     * reset here.
     */
    default void onEnter(QuestContext ctx) {}

    /**
     * Performs the step's work. Returns a {@link StepResult} interpreted by
     * the router per the contract on {@link StepResult}.
     */
    StepResult execute(QuestContext ctx);

    /**
     * Per-step completion predicate, evaluated by the router after
     * {@link #execute(QuestContext)} returns {@link StepResult.Done}. Polled
     * until {@code true} or the deadline elapses; only then does the router
     * re-pick.
     */
    Predicate<QuestContext> success();

    /** Hard deadline budget for one execute + success-poll cycle. */
    default Duration timeout() {
        return Duration.ofSeconds(30);
    }

    /** Higher-priority steps are picked first when more than one applies. */
    default int priority() {
        return 0;
    }
}
