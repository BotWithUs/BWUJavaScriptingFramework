package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.runtime.LastCrash;

/**
 * Host-side observer of script runner lifecycles.
 *
 * <p>This is how the host learns that a script started, stopped or stalled
 * without those facts becoming script-visible {@code GameEvent}s: the event
 * hierarchy in the API is public and sealed, and none of these are events a
 * script should subscribe to. The runtime calls this interface; the host
 * implements it.</p>
 *
 * <p>Every method has an empty default, so an implementation overrides only
 * what it needs. Calls arrive on runner threads and on the liveness watchdog's
 * thread, so an implementation must be thread-safe and must return quickly —
 * hand anything slow to another thread. An implementation that throws is
 * logged and otherwise ignored; it can never stop a script.</p>
 *
 * <p>A client script's crash is deliberately absent: it already leaves the
 * runner as a {@code ScriptCrashedEvent} on its connection's event bus, which
 * is where the host picks it up. A management script has no connection and no
 * event bus, so its crash comes through here.</p>
 *
 * <p>A script whose {@code onStart} throws never started, so it produces
 * neither {@link #scriptStarted} nor {@link #scriptStopped}: its crash is the
 * only thing reported.</p>
 */
public interface RunnerListener {

    /** Listens to nothing. The default for a runner no host is observing. */
    RunnerListener NONE = new RunnerListener() { };

    /**
     * A script finished {@code onStart} and is about to enter its loop.
     *
     * @param connectionName the connection the script runs on
     * @param scriptName     the script's manifest name
     */
    default void scriptStarted(String connectionName, String scriptName) { }

    /**
     * A script's run ended and its {@code onStop} has returned.
     *
     * @param connectionName the connection the script ran on
     * @param scriptName     the script's manifest name
     */
    default void scriptStopped(String connectionName, String scriptName) { }

    /**
     * The liveness watchdog judged a script unresponsive: it has sat inside one
     * {@code onLoop()} for too long, or has not honoured a stop request.
     *
     * @param connectionName the connection the script runs on
     * @param scriptName     the script's manifest name
     */
    default void scriptStalled(String connectionName, String scriptName) { }

    /**
     * A management script's lifecycle hook threw.
     *
     * @param scriptName the management script's manifest name
     * @param crash      which phase failed, and why
     */
    default void managementScriptCrashed(String scriptName, LastCrash crash) { }
}
