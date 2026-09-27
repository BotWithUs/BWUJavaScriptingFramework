package com.botwithus.bot.cli.gui.pages.dashboard;

/**
 * Everything the Dashboard can ask the host to do. Called from the render
 * thread; anything slow runs elsewhere and returns at once. Each method is a
 * no-op if what it names has gone.
 */
public interface DashboardActions {

    void stop(RunnerRef runner);

    /** Starts a stopped runner, or starts a crashed one again. */
    void run(RunnerRef runner);

    /** Opens the shared inspector on the runner's settings. */
    void openSettings(RunnerRef runner);

    /** Prints the runner's thread stack to the console. */
    void threadDump(RunnerRef runner);

    /** Tears the client's connection down and connects to its pipe again. */
    void reconnect(String pipe);

    /** Runs the {@code reload} command on the command thread. */
    void reload();

    /** Turns the Watch setting on or off. */
    void setWatch(boolean isOn);

    /** Turns the Restart-after-reload setting on or off. */
    void setRestartAfterReload(boolean isOn);

    /** Zeroes every runner's loop figures and every client's RPC figures, and restarts the clock. */
    void resetMetrics();

    /** Echoes {@code line} to the console and runs it as a command on the command thread. */
    void submit(String line);

    /** Prints {@code line} to the console, dimmed, as a note rather than a command's output. */
    void note(String line);

    /** Makes {@code pipe} the console target, where commands run. */
    void setConsoleTarget(String pipe);

    /** Empties the log buffer. */
    void clearLogs();
}
