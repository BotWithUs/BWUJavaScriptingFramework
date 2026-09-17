package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Engage an NPC by name. Targets the nearest matching NPC and clicks
 * "Attack"; success when no living NPC of that name remains in scene.
 * Intentionally minimal — combat itself runs through the producer's
 * action loop once a target is engaged; this step only handles the
 * engage + survival gate.
 */
public final class KillNpcStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final String npcName;
    private Duration timeout = DEFAULT_TIMEOUT;

    public KillNpcStep(String npcName) {
        this.npcName = npcName;
    }

    public KillNpcStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "killNpc(" + npcName + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        Npc target = ctx.api().npcs().nearest(npcName);
        if (target == null) {
            return StepResult.done();
        }
        if (!target.interact("Attack")) {
            return StepResult.retry("NPC '" + npcName + "' has no Attack option");
        }
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> {
            Npc nearest = ctx.api().npcs().nearest(npcName);
            return nearest == null || !nearest.isAlive();
        };
    }

    @Override
    public Duration timeout() {
        return timeout;
    }
}
