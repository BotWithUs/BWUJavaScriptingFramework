package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.Navigation;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.debug.ScriptContextPublisher;

import java.util.Objects;

/**
 * Handle passed into {@link QuestStep#execute(QuestContext)} and into the
 * success-predicate {@link QuestStep#success()}. Bundles the script's
 * {@link ScriptContext}, the active {@link QuestState}, and the router's
 * per-step deadline / retry budget into one object so step bodies can read
 * what they need without threading five arguments through.
 *
 * <p>Identity is per-step-invocation: every router cycle constructs a fresh
 * context with the current state, the absolute deadline for that
 * invocation, and the attempt counter. Do not cache it across loops.</p>
 */
public final class QuestContext {

    private final ScriptContext ctx;
    private final QuestId quest;
    private final QuestState state;
    private final long deadlineMs;
    private final int attempt;

    public QuestContext(ScriptContext ctx, QuestId quest, QuestState state,
                        long deadlineMs, int attempt) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.quest = Objects.requireNonNull(quest, "quest");
        this.state = Objects.requireNonNull(state, "state");
        this.deadlineMs = deadlineMs;
        this.attempt = attempt;
    }

    /** Underlying script context — for direct event-bus / message-bus access. */
    public ScriptContext script() {
        return ctx;
    }

    /** Convenience: {@code script().getGameAPI()}. */
    public GameAPI api() {
        return ctx.getGameAPI();
    }

    /** Convenience: {@code script().getNavigation()}. */
    public Navigation navigation() {
        return ctx.getNavigation();
    }

    /** Convenience: {@code script().getScriptContext()} — debugger publisher. */
    public ScriptContextPublisher publisher() {
        return ctx.getScriptContext();
    }

    /** The quest whose router constructed this context. */
    public QuestId quest() {
        return quest;
    }

    /** Tracker snapshot at the moment the router picked the active step. */
    public QuestState state() {
        return state;
    }

    /** Absolute deadline (epoch ms) at which the router will fail the step. */
    public long deadlineMs() {
        return deadlineMs;
    }

    /** Milliseconds remaining before the deadline, clamped to {@code >= 0}. */
    public long remainingMs() {
        return Math.max(0, deadlineMs - System.currentTimeMillis());
    }

    /** {@code true} once the deadline has been reached. */
    public boolean expired() {
        return System.currentTimeMillis() >= deadlineMs;
    }

    /** Attempt counter — {@code 0} on the first run, incremented per retry. */
    public int attempt() {
        return attempt;
    }
}
