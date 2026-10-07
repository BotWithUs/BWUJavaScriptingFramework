package com.botwithus.bot.core.worldwalker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chooses between a plain WorldWalker entry point and its {@code disabledMoves}
 * twin ({@code ww_executor_run} / {@code ww_executor_run_ex},
 * {@code ww_query_ex} / {@code ww_query_moves}) for one call.
 *
 * <p>The twin is an optional symbol: a {@code worldwalker.dll} older than it still
 * loads and still walks. The rule is:</p>
 * <ul>
 *   <li>an empty mask always takes the plain entry point, so a host with the
 *       restriction off makes exactly the call it made before the twin existed;</li>
 *   <li>a non-empty mask takes the twin when the library has it;</li>
 *   <li>otherwise it falls back to the plain entry point, unrestricted, and warns
 *       once per entry point for the life of the process. Refusing the walk instead
 *       would turn an opt-in routing preference into a dead walker on every host
 *       that has not updated its library.</li>
 * </ul>
 *
 * <p>Holds no native handle itself, so the choice is testable without the
 * library: the caller passes the two calls as lambdas, and only the one chosen
 * runs.</p>
 */
final class MovesEntry {

    private static final Logger log = LoggerFactory.getLogger(MovesEntry.class);

    /** The plain entry point's call. */
    @FunctionalInterface
    interface PlainCall {
        int invoke() throws Throwable;
    }

    /** The twin's call, given the mask to pass. */
    @FunctionalInterface
    interface MaskedCall {
        int invoke(int disabledMoves) throws Throwable;
    }

    private final String symbol;
    private final boolean isAvailable;
    private final AtomicBoolean hasWarned = new AtomicBoolean();

    /**
     * @param symbol      the twin's native name, for the warning
     * @param isAvailable whether the loaded library exports it
     */
    MovesEntry(String symbol, boolean isAvailable) {
        this.symbol = symbol;
        this.isAvailable = isAvailable;
    }

    /** Whether the loaded library exports the twin. */
    boolean isAvailable() {
        return isAvailable;
    }

    /**
     * Makes the call {@code moves} calls for.
     *
     * @return whatever the chosen entry point returned
     */
    int call(DisabledMoves moves, PlainCall plain, MaskedCall masked) throws Throwable {
        if (moves.isNone()) {
            return plain.invoke();
        }
        if (isAvailable) {
            return masked.invoke(moves.mask());
        }
        if (hasWarned.compareAndSet(false, true)) {
            log.warn("worldwalker.dll has no {}: routing WITHOUT the requested restriction"
                    + " (disabledMoves=0x{}). Update worldwalker.dll to apply it.",
                    symbol, Integer.toHexString(moves.mask()));
        }
        return plain.invoke();
    }
}
