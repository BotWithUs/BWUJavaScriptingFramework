package com.botwithus.bot.quest;

import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.api.script.TaskScript;

import java.util.List;
import java.util.Objects;

/**
 * Base class for declarative quest scripts. Subclasses bind a {@link QuestId}
 * (typically from the generated {@code Quests} constants) and override
 * {@link #defineSteps()} to declare the progression as a list of
 * {@link QuestStep}s composed through the {@link Steps} DSL.
 *
 * <p>This class handles the boilerplate: pre-flight requirement check,
 * {@link QuestProgressTracker} lifecycle, single-task router registration,
 * lifecycle/state publication, and orderly shutdown.</p>
 *
 * <pre>{@code
 * @ScriptManifest(name = "Cook's Assistant", version = "1.0",
 *                 author = "BotWithUs", category = ScriptCategory.QUEST)
 * public final class CooksAssistantScript extends QuestScript {
 *     public CooksAssistantScript() { super(Quests.COOKS_ASSISTANT); }
 *
 *     @Override
 *     protected List<QuestStep> defineSteps() {
 *         return List.of(...);
 *     }
 * }
 * }</pre>
 */
public abstract class QuestScript extends TaskScript {

    protected final QuestId quest;
    private QuestProgressTracker tracker;
    private boolean preflightFailed;

    protected QuestScript(QuestId quest) {
        this.quest = Objects.requireNonNull(quest, "quest");
    }

    /** Subclasses return the ordered list of steps. Called once during {@code onStart}. */
    protected abstract List<QuestStep> defineSteps();

    @Override
    protected final void setupTasks() {
        ScriptContext context = this.ctx;
        ScriptContextPublisher publisher = context.getScriptContext();
        publisher.state("STARTING");
        publisher.annotation("quest.id", quest.id());
        publisher.annotation("quest.name", quest.name());

        RequirementCheck check = QuestRequirements.check(quest, context.getGameAPI());
        if (!check.ok()) {
            publisher.annotation("quest.requirements.missing", missingSummary(check));
            publisher.trace("ERROR", "missing requirements: " + check.missing());
            publisher.state("STOPPED", "missing requirements");
            preflightFailed = true;
            return;
        }

        List<QuestStep> steps;
        try {
            steps = defineSteps();
        } catch (RuntimeException e) {
            publisher.trace("ERROR", "defineSteps() threw: " + e.getMessage());
            publisher.state("STOPPED", "defineSteps failed");
            preflightFailed = true;
            return;
        }
        if (steps == null || steps.isEmpty()) {
            publisher.trace("ERROR", "defineSteps() returned no steps");
            publisher.state("STOPPED", "no steps defined");
            preflightFailed = true;
            return;
        }

        this.tracker = new QuestProgressTracker(quest, context);
        addTask(new QuestRouterTask(quest, tracker, steps, context));
        publisher.state("RUNNING");
    }

    @Override
    public int onLoop() {
        if (preflightFailed) {
            return -1;
        }
        return super.onLoop();
    }

    @Override
    public void onStop() {
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }
        super.onStop();
    }

    /** Visible for test subclasses that want to inspect the live tracker. */
    protected QuestProgressTracker tracker() {
        return tracker;
    }

    private static String missingSummary(RequirementCheck check) {
        return check.missing().toString();
    }
}
