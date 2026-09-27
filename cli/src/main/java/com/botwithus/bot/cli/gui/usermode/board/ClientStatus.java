package com.botwithus.bot.cli.gui.usermode.board;

import java.util.OptionalInt;

/**
 * The state a client card shows. Each variant carries only what its card needs,
 * and the card renders by an exhaustive switch, so adding a state is a compile
 * error everywhere it is not yet handled.
 */
public sealed interface ClientStatus {

    /** A script is running; {@code recentLoopNanos} is oldest first. */
    record Running(ScriptInfo script, double avgLoopMs, long[] recentLoopNanos) implements ClientStatus {}

    /** Connected and logged in, nothing running. */
    record Idle() implements ClientStatus {}

    /** The pipe is open but the account reply has not arrived yet. */
    record Loading() implements ClientStatus {}

    /**
     * The pipe is gone and nothing is retrying.
     *
     * @param wasRunning the script that was running when contact was lost, or {@code null}
     * @param canRetry   {@code false} once the client's game process has exited: its pipe
     *                   can never come back, so the card offers Forget instead of a retry
     */
    record Lost(long silentForMillis, ScriptInfo wasRunning, boolean canRetry) implements ClientStatus {}

    /**
     * A reconnect attempt is scheduled or in flight; {@code attempt} is 1-indexed.
     *
     * @param maxAttempts the attempt budget, or empty when there is none
     */
    record Reconnecting(int attempt, OptionalInt maxAttempts, long nextDelayMs) implements ClientStatus {}

    /**
     * The last run of {@code script} ended by throwing.
     *
     * @param summary one line, e.g. "NullPointerException in onLoop()"
     */
    record Crashed(ScriptInfo script, String summary) implements ClientStatus {}

    /** Lost, reconnecting and crashed clients are what "Needs attention" shows. */
    default boolean needsAttention() {
        return switch (this) {
            case Lost ignored -> true;
            case Reconnecting ignored -> true;
            case Crashed ignored -> true;
            case Running ignored -> false;
            case Idle ignored -> false;
            case Loading ignored -> false;
        };
    }

    default boolean isRunning() {
        return switch (this) {
            case Running ignored -> true;
            case Idle ignored -> false;
            case Loading ignored -> false;
            case Lost ignored -> false;
            case Reconnecting ignored -> false;
            case Crashed ignored -> false;
        };
    }
}
