package com.botwithus.bot.skilling.plan;

/**
 * Cross-skill production orchestrator — the seam that turns a {@link Goal} into
 * coordinated skill-script runs. <b>Designed this cut; the implementation lands
 * with the first production skill (Smithing).</b>
 *
 * <p>The intended flow, given a {@link Goal.Production}: expand it with the
 * {@link Planner} into a {@link com.botwithus.bot.skilling.atlas.BuildPlan}; walk
 * the plan bottom-up; for each unmet raw material check bank + backpack (+ metal
 * bank) counts and, when short, dispatch the matching gather script (resolved via
 * each script's {@link com.botwithus.bot.skilling.script.Capability}) through the
 * api's {@code ScriptManager}; monitor counts and hand off via the api's
 * {@code MessageBus}/{@code SharedState}; then run the craft step. v1 ships only
 * this contract plus the {@link Planner}/closure it builds on — no concrete
 * dispatch yet.</p>
 */
public interface Orchestrator {

    /** Begin (or resume) pursuing a goal: plan it, then gather/craft toward it. */
    void pursue(Goal goal);

    /** Whether the current goal's target has been met. */
    boolean isComplete();
}
