package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.script.Task;

import java.util.List;
import java.util.function.Predicate;

/**
 * Single {@link Task} that drives quest progression. On each call it
 * refreshes the {@link QuestState} from {@link QuestProgressTracker},
 * picks the highest-priority {@link QuestStep} whose
 * {@link QuestStep#appliesTo(QuestState, GameAPI)} returns {@code true}, and
 * dispatches one cycle of {@link QuestStep#execute(QuestContext)} →
 * {@link QuestStep#success()} polling, with retries and a step-scoped
 * deadline.
 *
 * <p>State machine boundary conditions:</p>
 * <ul>
 *   <li>No step applies → router declares the quest complete, publishes a
 *       {@code STOPPED} lifecycle and returns {@code -1}.</li>
 *   <li>Step's success poll times out → router records a retry; after
 *       {@link #MAX_RETRIES} unsuccessful attempts it aborts the script.</li>
 *   <li>Step returns {@link StepResult.Abort} → router publishes the reason
 *       and aborts the script.</li>
 *   <li>{@link RuntimeException} from a step body is caught and treated as
 *       a retry (the producer pipe can throw transiently and we don't want
 *       a single hiccup to drop the script).</li>
 * </ul>
 */
final class QuestRouterTask implements Task {

    /** Maximum consecutive failed attempts on the same step before aborting. */
    static final int MAX_RETRIES = 3;
    /** Delay returned after a retry. */
    private static final int RETRY_BACKOFF_MS = 2_000;
    /** Delay returned after a successful step cycle (default re-tick). */
    private static final int TICK_DELAY_MS = 600;
    /** Polling interval while waiting for a step's success predicate. */
    private static final long SUCCESS_POLL_INTERVAL_MS = 200L;

    private final QuestId quest;
    private final QuestProgressTracker tracker;
    private final List<QuestStep> steps;
    private final ScriptContext ctx;
    private final GameAPI api;
    private final ScriptContextPublisher publisher;

    private QuestStep currentStep;
    private int attempt;
    private boolean finished;

    QuestRouterTask(QuestId quest, QuestProgressTracker tracker,
                    List<QuestStep> steps, ScriptContext ctx) {
        this.quest = quest;
        this.tracker = tracker;
        this.steps = List.copyOf(steps);
        this.ctx = ctx;
        this.api = ctx.getGameAPI();
        this.publisher = ctx.getScriptContext();
    }

    @Override
    public String name() {
        return "quest.router(" + quest.name() + ")";
    }

    @Override
    public boolean validate() {
        return !finished;
    }

    @Override
    public int execute() {
        QuestState state = tracker.current();
        QuestStep step;
        try {
            step = pick(state);
        } catch (RuntimeException e) {
            return retry(currentStep, "pick threw: " + e.getMessage());
        }
        if (step == null) {
            publisher.annotation("quest.state", state.values());
            publisher.annotation("quest.complete", true);
            publisher.trace("INFO", "quest '" + quest.name() + "' complete; no step applies");
            publisher.state("STOPPED", "quest complete");
            finished = true;
            return -1;
        }
        if (step != currentStep) {
            currentStep = step;
            attempt = 0;
            publisher.annotation("quest.current_step", step.name());
            publisher.annotation("quest.state", state.values());
            publisher.trace("INFO", "enter step '" + step.name() + "'");
            try {
                step.onEnter(buildCtx(state, step));
            } catch (RuntimeException e) {
                return retry(step, "onEnter threw: " + e.getMessage());
            }
        }
        QuestContext qctx = buildCtx(state, step);
        StepResult result;
        try {
            result = step.execute(qctx);
        } catch (RuntimeException e) {
            return retry(step, "execute threw: " + e.getMessage());
        }
        return dispatch(step, qctx, result);
    }

    private int dispatch(QuestStep step, QuestContext qctx, StepResult result) {
        return switch (result) {
            case StepResult.Done done -> awaitSuccess(step, qctx)
                    ? completeCycle()
                    : retry(step, "success poll timed out after " + step.timeout());
            case StepResult.Continue cont -> Math.max(50, cont.nextDelayMs());
            case StepResult.Retry r -> retry(step, r.reason());
            case StepResult.Abort a -> {
                publisher.trace("ERROR", "abort '" + step.name() + "': " + a.reason());
                publisher.annotation("quest.abort_reason", a.reason());
                publisher.state("STOPPED", "abort: " + a.reason());
                finished = true;
                yield -1;
            }
        };
    }

    private int completeCycle() {
        attempt = 0;
        return TICK_DELAY_MS;
    }

    private int retry(QuestStep step, String reason) {
        attempt++;
        String stepName = step == null ? "(none)" : step.name();
        if (attempt > MAX_RETRIES) {
            publisher.trace("ERROR", "aborting step '" + stepName + "' after " + MAX_RETRIES + " retries: " + reason);
            publisher.annotation("quest.abort_reason", "max retries: " + reason);
            publisher.state("STOPPED", "max retries on " + stepName);
            finished = true;
            return -1;
        }
        publisher.trace("WARN", "retry " + attempt + "/" + MAX_RETRIES + " for '" + stepName + "': " + reason);
        return RETRY_BACKOFF_MS;
    }

    private QuestStep pick(QuestState state) {
        QuestStep best = null;
        for (QuestStep candidate : steps) {
            if (!candidate.appliesTo(state, api)) {
                continue;
            }
            if (best == null || candidate.priority() > best.priority()) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean awaitSuccess(QuestStep step, QuestContext qctx) {
        Predicate<QuestContext> success = step.success();
        long deadline = qctx.deadlineMs();
        while (System.currentTimeMillis() < deadline) {
            QuestState state = tracker.current();
            QuestContext pollCtx = new QuestContext(
                    ctx, quest, state, deadline, attempt);
            if (success.test(pollCtx)) {
                return true;
            }
            try {
                Thread.sleep(SUCCESS_POLL_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private QuestContext buildCtx(QuestState state, QuestStep step) {
        long deadline = System.currentTimeMillis() + step.timeout().toMillis();
        return new QuestContext(ctx, quest, state, deadline, attempt);
    }
}
