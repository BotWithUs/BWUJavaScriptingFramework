package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.quest.steps.DispatchStep;
import com.botwithus.bot.quest.steps.NoopStep;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class DispatchStepTest {

    @Test
    void picksBranchByStateValue() {
        AtomicInteger redCount = new AtomicInteger();
        AtomicInteger yellowCount = new AtomicInteger();
        DispatchStep dispatch = new DispatchStep(s -> s.get(298))
                .on(1, recording(redCount))
                .on(2, recording(yellowCount))
                .otherwise(new NoopStep());

        QuestState red = new QuestState(Map.of(298, 1));
        QuestState yellow = new QuestState(Map.of(298, 2));
        QuestState unmatched = new QuestState(Map.of(298, 9));

        QuestContext redCtx = ctxWith(red);
        QuestContext yellowCtx = ctxWith(yellow);
        QuestContext otherCtx = ctxWith(unmatched);

        dispatch.execute(redCtx);
        dispatch.execute(yellowCtx);
        dispatch.execute(otherCtx);

        assertEquals(1, redCount.get(), "red branch must run when 298==1");
        assertEquals(1, yellowCount.get(), "yellow branch must run when 298==2");
    }

    @Test
    void appliesToFollowsBranchPredicate() {
        GameAPI api = Mockito.mock(GameAPI.class);
        DispatchStep dispatch = new DispatchStep(s -> s.get(298))
                .on(1, gated(state -> state.get(298) == 1, recording(new AtomicInteger())));

        assertTrue(dispatch.appliesTo(new QuestState(Map.of(298, 1)), api));
        assertFalse(dispatch.appliesTo(new QuestState(Map.of(298, 5)), api),
                "no branch + no otherwise should not apply");
    }

    @Test
    void fallsBackToOtherwiseWhenSelectorValueIsUnregistered() {
        AtomicInteger otherwiseCount = new AtomicInteger();
        DispatchStep dispatch = new DispatchStep(s -> 99)
                .otherwise(recording(otherwiseCount));

        dispatch.execute(ctxWith(new QuestState(Map.of())));
        assertEquals(1, otherwiseCount.get());
    }

    private static QuestStep recording(AtomicInteger counter) {
        return new QuestStep() {
            @Override public String name() { return "recording"; }
            @Override public boolean appliesTo(QuestState state, GameAPI api) { return true; }
            @Override public StepResult execute(QuestContext ctx) {
                counter.incrementAndGet();
                return StepResult.done();
            }
            @Override public Predicate<QuestContext> success() { return c -> true; }
        };
    }

    private static QuestStep gated(Predicate<QuestState> applies, QuestStep delegate) {
        return new QuestStep() {
            @Override public String name() { return "gated"; }
            @Override public boolean appliesTo(QuestState state, GameAPI api) {
                return applies.test(state);
            }
            @Override public StepResult execute(QuestContext ctx) { return delegate.execute(ctx); }
            @Override public Predicate<QuestContext> success() { return delegate.success(); }
        };
    }

    private static QuestContext ctxWith(QuestState state) {
        ScriptContext ctx = Mockito.mock(ScriptContext.class, RETURNS_DEEP_STUBS);
        QuestId quest = new QuestId(137, "Goblin Diplomacy", new int[]{ 297, 298 });
        return new QuestContext(ctx, quest, state,
                System.currentTimeMillis() + 5_000, 0);
    }
}
