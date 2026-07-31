package com.botwithus.bot.api.runtime;

/**
 * Thrown when a script that was asked to stop — and did not — tries to act on
 * the game.
 *
 * <p>Java has no way to kill a thread, so a script that refuses to leave
 * {@code onLoop} cannot be terminated. What the runtime can do is cut it off:
 * once a runner reaches {@link Liveness#REVOKED}, every RPC attempted from its
 * thread (or from a thread it spawned, such as the walk executor) throws this
 * instead of reaching the game. A script that ignored the stop therefore stops
 * having any effect, even though it is still running.</p>
 *
 * <p>In practice this usually ends the script for real: stuck scripts are
 * typically looping on an API call rather than spinning in pure computation, so
 * the exception unwinds them out of {@code onLoop}.</p>
 *
 * <p><strong>Scripts must not catch this.</strong> Swallowing it in a
 * {@code catch (Exception)} inside a retry loop turns a recoverable zombie into
 * an abandoned one. If you need cleanup on stop, use {@code onStop}, or poll
 * {@link com.botwithus.bot.api.ScriptContext#isStopRequested()} and return
 * from {@code onLoop} yourself.</p>
 */
public final class ScriptRevokedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String scriptName;

    public ScriptRevokedException(String scriptName) {
        super("Script '" + scriptName + "' was revoked after it ignored a stop request; "
                + "its access to the game has been withdrawn");
        this.scriptName = scriptName;
    }

    /** The script whose access was withdrawn. */
    public String scriptName() {
        return scriptName;
    }
}
