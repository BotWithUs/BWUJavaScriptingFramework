package com.botwithus.bot.api.runtime;

/**
 * Closed state machine describing the lifecycle of a reconnect controller.
 *
 * <p>Each variant carries the wall-clock {@link #timestamp()} at which the
 * transition was observed. Consumers dispatch with an exhaustive {@code switch}
 * over {@code ReconnectState}; the compiler enforces coverage.</p>
 *
 * <ul>
 *   <li>{@link Connected} — pipe is live; no reconnect is in progress.</li>
 *   <li>{@link Disconnected} — most recent disconnect, before any retry begins.
 *       Used as a transient transition state; the controller may move to
 *       {@link Reconnecting} immediately afterward.</li>
 *   <li>{@link Reconnecting} — a retry attempt is scheduled or in flight.
 *       {@link Reconnecting#attempt()} is 1-indexed.</li>
 *   <li>{@link GivingUp} — the policy's attempt budget was exhausted.</li>
 * </ul>
 *
 * <p>This type lives in the {@code api} module (not {@code core.rpc}) so that
 * {@code ReconnectStateChangedEvent} can reference it without inverting the
 * module dependency direction.</p>
 */
public sealed interface ReconnectState {

    long timestamp();

    record Connected(long timestamp) implements ReconnectState {}

    record Disconnected(long timestamp, Throwable cause) implements ReconnectState {}

    record Reconnecting(long timestamp, int attempt, long nextDelayMs) implements ReconnectState {}

    record GivingUp(long timestamp, int attempts, Throwable lastCause) implements ReconnectState {}
}
