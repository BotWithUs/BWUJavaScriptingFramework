package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Pure synchronisation primitive: block until a tracker var equals (or
 * exceeds) a target value. The classic varbit-gated step — paired at the
 * end of a sequence to confirm a server-side advance before the router
 * re-picks.
 *
 * <p>{@code execute} is a no-op; everything happens in
 * {@link #success()}, which the router polls.</p>
 */
public final class WaitForVarStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final int varId;
    private final int targetValue;
    private boolean orGreater;
    private Duration timeout = DEFAULT_TIMEOUT;

    public WaitForVarStep(int varId, int targetValue) {
        this.varId = varId;
        this.targetValue = targetValue;
    }

    /** Accept any value {@code >= targetValue}. */
    public WaitForVarStep orGreater() {
        this.orGreater = true;
        return this;
    }

    public WaitForVarStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "waitForVar(" + varId + (orGreater ? ">=" : "==") + targetValue + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        // Active while we have not yet reached the target. Past the target
        // (whether == or >=) the wait is satisfied and a later step picks up.
        return state.get(varId) < targetValue;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> matches(ctx.state().get(varId));
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private boolean matches(int value) {
        return orGreater ? value >= targetValue : value == targetValue;
    }
}
