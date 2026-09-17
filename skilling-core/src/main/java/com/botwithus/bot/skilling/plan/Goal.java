package com.botwithus.bot.skilling.plan;

/**
 * A user-level objective the orchestrator pursues. Either produce a quantity of an
 * item (which the {@link Planner} expands into a gather/craft dependency plan) or
 * train a skill to a level.
 */
public sealed interface Goal permits Goal.Production, Goal.Training {

    /** Produce {@code quantity} of item {@code itemId} (e.g. 100 bronze bars). */
    record Production(int itemId, int quantity) implements Goal {}

    /** Train {@code skillId} to {@code targetLevel}. */
    record Training(int skillId, int targetLevel) implements Goal {}
}
