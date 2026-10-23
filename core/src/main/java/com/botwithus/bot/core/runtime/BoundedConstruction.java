package com.botwithus.bot.core.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Makes one instance of a delivered script on a thread of its own, and stops
 * waiting for it after a time budget.
 *
 * <p>Making the instance runs the script's static initialisers and constructor,
 * which is third-party code. Run on the install thread, one that never returned
 * would hold the install, and the process-wide delivery lock with it, for good:
 * the row would stay on "Installing" with nothing in the log. Here the install
 * gets an answer within the budget whatever the script does. The budget starts
 * when construction starts, so time spent waiting behind another install is not
 * counted against it.
 *
 * <p>A construction that overruns cannot be stopped, since Java has no way to kill
 * a thread. It is left running on a daemon thread, named after the script so a
 * thread dump points at it.
 */
public final class BoundedConstruction {

    /** How one construction ended. */
    public sealed interface Outcome<T> {

        /** It returned {@code instance} after {@code took}. */
        record Built<T>(T instance, Duration took) implements Outcome<T> {}

        /** It threw {@code error}, an exception or an error, after {@code took}. */
        record Threw<T>(Throwable error, Duration took) implements Outcome<T> {}

        /** It had not returned when the budget ran out, and is still running. */
        record TimedOut<T>(Duration budget) implements Outcome<T> {}

        /**
         * The thread waiting for it was interrupted. Says nothing about the script,
         * which is still running; the caller should stop rather than blame it.
         */
        record Interrupted<T>() implements Outcome<T> {}
    }

    private static final String THREAD_PREFIX = "sdn-construct-";

    private BoundedConstruction() {
    }

    /**
     * Runs {@code construct} on a new daemon thread named for {@code what} and waits
     * at most {@code budget} for it.
     */
    public static <T> Outcome<T> construct(String what, Supplier<T> construct, Duration budget) {
        FutureTask<T> task = new FutureTask<>(construct::get);
        // rule-exception: §Modern Java (virtual threads). This runs untrusted script
        // code that may never return; a platform thread keeps a stuck one from
        // occupying a virtual-thread carrier. See CLAUDE.md, "Script runner threads
        // are platform threads".
        Thread.ofPlatform().daemon().name(THREAD_PREFIX + what).start(task);
        long start = System.nanoTime();
        try {
            T instance = task.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            return new Outcome.Built<>(instance, since(start));
        } catch (TimeoutException e) {
            return new Outcome.TimedOut<>(budget);
        } catch (ExecutionException e) {
            return new Outcome.Threw<>(e.getCause(), since(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Interrupted<>();
        }
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
