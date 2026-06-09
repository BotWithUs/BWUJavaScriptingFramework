package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.quest.steps.WaitForVarStep;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

class WaitForVarStepTest {

    @Test
    void appliesOnlyWhenVarHasNotYetReachedTarget() {
        WaitForVarStep step = new WaitForVarStep(2492, 2);
        GameAPI api = Mockito.mock(GameAPI.class);

        assertTrue(step.appliesTo(new QuestState(Map.of(2492, 1)), api));
        assertTrue(step.appliesTo(new QuestState(Map.of(2492, 0)), api));
        assertFalse(step.appliesTo(new QuestState(Map.of(2492, 2)), api));
        assertFalse(step.appliesTo(new QuestState(Map.of(2492, 3)), api));
    }

    @Test
    void orGreaterAcceptsAnyValueAtOrAboveTarget() {
        WaitForVarStep step = new WaitForVarStep(2492, 2).orGreater();
        GameAPI api = Mockito.mock(GameAPI.class);

        assertFalse(step.appliesTo(new QuestState(Map.of(2492, 2)), api));
        assertFalse(step.appliesTo(new QuestState(Map.of(2492, 3)), api));
        assertTrue(step.appliesTo(new QuestState(Map.of(2492, 1)), api));
    }

    @Test
    void executeIsAlwaysDoneSinceWorkLivesInSuccess() {
        WaitForVarStep step = new WaitForVarStep(2492, 2);
        ScriptContext ctx = Mockito.mock(ScriptContext.class, RETURNS_DEEP_STUBS);
        QuestId quest = new QuestId(257, "Cook's Assistant", new int[]{ 2492 });
        QuestContext qctx = new QuestContext(
                ctx, quest, new QuestState(Map.of(2492, 1)),
                System.currentTimeMillis() + 5_000, 0);

        StepResult result = step.execute(qctx);
        assertEquals(StepResult.DONE, result);
    }

    @Test
    void successResolvesWhenTrackerStateMatches() {
        WaitForVarStep step = new WaitForVarStep(2492, 2);
        ScriptContext ctx = Mockito.mock(ScriptContext.class, RETURNS_DEEP_STUBS);
        QuestId quest = new QuestId(257, "Cook's Assistant", new int[]{ 2492 });

        QuestContext stillInProgress = new QuestContext(
                ctx, quest, new QuestState(Map.of(2492, 1)),
                System.currentTimeMillis() + 5_000, 0);
        QuestContext done = new QuestContext(
                ctx, quest, new QuestState(Map.of(2492, 2)),
                System.currentTimeMillis() + 5_000, 0);

        assertFalse(step.success().test(stillInProgress));
        assertTrue(step.success().test(done));
    }
}
