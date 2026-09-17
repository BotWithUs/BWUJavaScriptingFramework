package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Chains child steps under a shared {@code appliesTo} gate. Acts as its own
 * mini-router for the children: each child's {@code execute}/{@code success}
 * cycle runs to completion before the next child starts, all within the
 * outer router's single {@code execute} call.
 *
 * <p>Gates compose through builder methods:
 *  {@link #whenVar(int, int)},
 *  {@link #whenVarInRange(int, int, int)},
 *  {@link #andBackpackHas(int...)},
 *  {@link #andBackpackHasName(String...)},
 *  {@link #andBackpackMissing(int...)},
 *  {@link #andBackpackMissingName(String...)},
 *  {@link #andCondition(BiPredicate)}.</p>
 */
public final class SequenceStep implements QuestStep {

    private static final long INTER_CHILD_SETTLE_MS = 250;
    private static final long SUCCESS_POLL_INTERVAL_MS = 200;

    private final List<QuestStep> children;
    private BiPredicate<QuestState, GameAPI> gate = (s, a) -> true;
    private Duration timeout;
    private String name;

    public SequenceStep(List<QuestStep> children) {
        this.children = List.copyOf(children);
    }

    public SequenceStep named(String name) {
        this.name = name;
        return this;
    }

    public SequenceStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    public SequenceStep whenVar(int varId, int value) {
        return andCondition((state, api) -> state.has(varId, value));
    }

    public SequenceStep whenVarInRange(int varId, int lo, int hi) {
        return andCondition((state, api) -> state.inRange(varId, lo, hi));
    }

    public SequenceStep andBackpackHas(int... itemIds) {
        return andCondition((state, api) -> hasAll(api.backpack(), itemIds));
    }

    public SequenceStep andBackpackHasName(String... names) {
        return andCondition((state, api) -> hasAllByName(api.backpack(), names));
    }

    public SequenceStep andBackpackMissing(int... itemIds) {
        return andCondition((state, api) -> missingAny(api.backpack(), itemIds));
    }

    public SequenceStep andBackpackMissingName(String... names) {
        return andCondition((state, api) -> missingAnyByName(api.backpack(), names));
    }

    public SequenceStep andCondition(BiPredicate<QuestState, GameAPI> extra) {
        BiPredicate<QuestState, GameAPI> prior = gate;
        this.gate = (s, a) -> prior.test(s, a) && extra.test(s, a);
        return this;
    }

    @Override
    public String name() {
        if (name != null) {
            return name;
        }
        StringBuilder sb = new StringBuilder("sequence(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(children.get(i).name());
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return gate.test(state, api);
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        long sequenceDeadline = System.currentTimeMillis() + timeout().toMillis();
        for (QuestStep child : children) {
            if (Thread.currentThread().isInterrupted()) {
                return StepResult.abort("interrupted in sequence " + name());
            }
            child.onEnter(ctx);
            StepResult outcome = runChild(child, ctx, sequenceDeadline);
            if (!(outcome instanceof StepResult.Done)) {
                return outcome;
            }
            sleep(INTER_CHILD_SETTLE_MS);
        }
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> true;
    }

    @Override
    public Duration timeout() {
        if (timeout != null) {
            return timeout;
        }
        long ms = 0;
        for (QuestStep child : children) {
            ms += child.timeout().toMillis() + INTER_CHILD_SETTLE_MS;
        }
        return Duration.ofMillis(Math.max(ms, Duration.ofMinutes(2).toMillis()));
    }

    private StepResult runChild(QuestStep child, QuestContext outer, long sequenceDeadline) {
        long childDeadline = Math.min(
                sequenceDeadline,
                System.currentTimeMillis() + child.timeout().toMillis());
        while (true) {
            if (System.currentTimeMillis() >= childDeadline) {
                return StepResult.retry("child '" + child.name() + "' execute deadline reached");
            }
            QuestContext childCtx = new QuestContext(
                    outer.script(), outer.quest(), outer.state(),
                    childDeadline, outer.attempt());
            StepResult result = child.execute(childCtx);
            switch (result) {
                case StepResult.Done done -> {
                    return awaitChildSuccess(child, outer, childDeadline)
                            ? StepResult.done()
                            : StepResult.retry("child '" + child.name() + "' success poll timed out");
                }
                case StepResult.Continue cont -> {
                    long wait = Math.max(50, cont.nextDelayMs());
                    sleep(Math.min(wait, Math.max(0, childDeadline - System.currentTimeMillis())));
                }
                case StepResult.Retry retry -> {
                    return retry;
                }
                case StepResult.Abort abort -> {
                    return abort;
                }
            }
        }
    }

    private boolean awaitChildSuccess(QuestStep child, QuestContext outer, long deadline) {
        Predicate<QuestContext> pred = child.success();
        while (System.currentTimeMillis() < deadline) {
            QuestContext childCtx = new QuestContext(
                    outer.script(), outer.quest(), outer.state(),
                    deadline, outer.attempt());
            if (pred.test(childCtx)) {
                return true;
            }
            sleep(SUCCESS_POLL_INTERVAL_MS);
        }
        return false;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasAll(Backpack bag, int[] ids) {
        for (int id : ids) {
            if (!bag.contains(id)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAllByName(Backpack bag, String[] names) {
        return Arrays.stream(names).allMatch(bag::contains);
    }

    private static boolean missingAny(Backpack bag, int[] ids) {
        for (int id : ids) {
            if (!bag.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean missingAnyByName(Backpack bag, String[] names) {
        return Arrays.stream(names).anyMatch(n -> !bag.contains(n));
    }

    /** Exposed for tests. */
    public List<QuestStep> children() {
        return new ArrayList<>(children);
    }
}
