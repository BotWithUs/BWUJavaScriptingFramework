package com.botwithus.bot.core.worldwalker;

/**
 * Thrown when a WorldWalker downcall fails for a reason other than a planner
 * "no route" miss (which surfaces as {@code null} from {@link WorldWalker#query}).
 *
 * <p>Wraps both invalid-argument errors from the C ABI and unexpected internal
 * failures. The message preserves the thread-local {@code ww_last_error()}
 * string when one is available.</p>
 */
public final class WorldWalkerException extends RuntimeException {

    public WorldWalkerException(String message) {
        super(message);
    }

    public WorldWalkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
