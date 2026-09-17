package com.botwithus.bot.quest;

/**
 * Outcome of a single {@link QuestStep#execute(QuestContext)} invocation.
 *
 * <p>The router task interprets each result as follows:</p>
 * <ul>
 *   <li>{@link Done} — step completed; advance to the next loop iteration
 *       (which re-picks based on the next tracker reading).</li>
 *   <li>{@link Continue} — step is mid-progress and wants to be re-entered
 *       after {@code nextDelayMs}; no retry counter bump.</li>
 *   <li>{@link Retry} — step failed in a way that may resolve on its own;
 *       router schedules another attempt up to the retry budget, then
 *       upgrades to an {@link Abort}.</li>
 *   <li>{@link Abort} — terminal failure; router stops the script with the
 *       given reason on the publisher.</li>
 * </ul>
 */
public sealed interface StepResult {

    /** Step completed; re-evaluate dispatch on next loop. */
    record Done() implements StepResult {}

    /** Step mid-progress; re-enter after {@code nextDelayMs} milliseconds. */
    record Continue(int nextDelayMs) implements StepResult {}

    /** Recoverable failure; router will retry with backoff. */
    record Retry(String reason) implements StepResult {}

    /** Terminal failure; router stops the script. */
    record Abort(String reason) implements StepResult {}

    /** Singleton {@link Done} — there's only one shape, share it. */
    StepResult DONE = new Done();

    /** Returns the shared {@link Done} singleton. */
    static StepResult done() {
        return DONE;
    }

    /** Returns a {@link Continue} carrying the given re-entry delay. */
    static StepResult continueIn(int nextDelayMs) {
        return new Continue(nextDelayMs);
    }

    /** Returns a {@link Retry} carrying the given reason. */
    static StepResult retry(String reason) {
        return new Retry(reason);
    }

    /** Returns an {@link Abort} carrying the given reason. */
    static StepResult abort(String reason) {
        return new Abort(reason);
    }
}
