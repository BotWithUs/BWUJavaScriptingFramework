package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Selector-driven branching step. Picks one of N configured branches based
 * on a function over {@link QuestState}, falling back to an optional
 * {@code otherwise} branch when no key matches.
 *
 * <pre>{@code
 * Steps.dispatch(state -> state.get(298))
 *      .on(1, Steps.talkTo("General Wartface").selectOption("Red"))
 *      .on(2, Steps.talkTo("General Wartface").selectOption("Yellow"))
 *      .otherwise(Steps.noop());
 * }</pre>
 */
public final class DispatchStep implements QuestStep {

    private final Function<QuestState, Integer> selector;
    private final Map<Integer, QuestStep> branches = new HashMap<>();
    private QuestStep otherwise;

    public DispatchStep(Function<QuestState, Integer> selector) {
        this.selector = selector;
    }

    public DispatchStep on(int value, QuestStep step) {
        branches.put(value, step);
        return this;
    }

    public DispatchStep otherwise(QuestStep step) {
        this.otherwise = step;
        return this;
    }

    @Override
    public String name() {
        return "dispatch(" + branches.keySet() + (otherwise != null ? " +otherwise" : "") + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        QuestStep branch = resolve(state);
        return branch != null && branch.appliesTo(state, api);
    }

    @Override
    public void onEnter(QuestContext ctx) {
        QuestStep branch = resolve(ctx.state());
        if (branch != null) {
            branch.onEnter(ctx);
        }
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        QuestStep branch = resolve(ctx.state());
        return branch == null
                ? StepResult.retry("dispatch selector returned no matching branch")
                : branch.execute(ctx);
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> {
            QuestStep branch = resolve(ctx.state());
            return branch != null && branch.success().test(ctx);
        };
    }

    @Override
    public Duration timeout() {
        Duration longest = Duration.ofSeconds(30);
        for (QuestStep s : branches.values()) {
            if (s.timeout().compareTo(longest) > 0) {
                longest = s.timeout();
            }
        }
        if (otherwise != null && otherwise.timeout().compareTo(longest) > 0) {
            longest = otherwise.timeout();
        }
        return longest;
    }

    private QuestStep resolve(QuestState state) {
        Integer key = selector.apply(state);
        if (key == null) {
            return otherwise;
        }
        return branches.getOrDefault(key, otherwise);
    }
}
