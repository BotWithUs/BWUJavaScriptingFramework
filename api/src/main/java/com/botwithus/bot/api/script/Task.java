package com.botwithus.bot.api.script;

/**
 * A discrete unit of work within a {@link TaskScript}.
 * Tasks are evaluated by priority; the first that validates is executed.
 */
public interface Task {

    /**
     * Display name for logging/debugging.
     */
    String name();

    /**
     * Returns true if this task should execute on the current loop iteration.
     */
    boolean validate();

    /**
     * Executes this task. Returns the loop delay in milliseconds, or -1 to stop the script.
     */
    int execute();

    /**
     * Higher priority tasks are checked first. Default is 0.
     */
    default int priority() {
        return 0;
    }
}
