package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.runtime.ScriptRevokedException;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, for the calling thread, whether the script it belongs to is still
 * allowed to act on the game.
 *
 * <p>One gate per connection, shared by that connection's {@link ScriptRuntime}
 * and its {@code RpcClient}. The runtime tags each script thread on entry; the
 * RPC client asks the gate before every call and throws
 * {@link ScriptRevokedException} once that script has been revoked. Because all
 * three {@code callSync*} methods funnel through a single path, that is one
 * check covering the whole game-facing surface — no need to wrap the ~100-method
 * {@code GameAPI} interface.</p>
 *
 * <p>The tag is carried by an {@link InheritableThreadLocal}, so a thread the
 * script spawns is covered too. That is what catches the walk executor
 * ({@code ww-executor-<nanos>}, started from the script's own thread inside
 * {@code GameAPIImpl.walkWorldPathAsync}), which would otherwise keep issuing
 * {@code queue_action} calls long after the script that started it had stopped.</p>
 *
 * <p>Note this is deliberately an <em>instance</em>, not a static holder: the
 * thread-local lives on the gate object, so per-connection state stays per
 * connection and no process-global mutable state is introduced.</p>
 */
public final class ScriptGate {

    private final InheritableThreadLocal<String> currentScript = new InheritableThreadLocal<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    public ScriptGate() {}

    /** Tags the calling thread — and any thread it later spawns — as belonging to {@code scriptName}. */
    public void enter(String scriptName) {
        currentScript.set(scriptName);
    }

    /** Clears the calling thread's tag. */
    public void exit() {
        currentScript.remove();
    }

    /** The script the calling thread belongs to, or {@code null} for host threads. */
    public String current() {
        return currentScript.get();
    }

    /**
     * Withdraws {@code scriptName}'s access to the game. Permanent for the life
     * of the connection: a revoked script's thread can't be killed, so it must
     * never be trusted again, and the runner refuses to restart under the same
     * name ({@link ScriptRunner#start()} guards on terminal liveness).
     */
    public void revoke(String scriptName) {
        revoked.add(scriptName);
    }

    /**
     * How long a revoked caller is parked before its call is rejected.
     *
     * <p>Not politeness — throttling. A revoked script that swallows the
     * exception retries immediately, and the most common script shape,
     * {@code try { api.doThing(); sleep(n); } catch (Exception e) {}}, never
     * reaches its own sleep because the throw happens first. Without a brake
     * here that becomes an unbounded retry loop: measured at a full core burned
     * continuously, almost all of it in {@code fillInStackTrace}. Parking the
     * caller bounds a revoked script to a few rejected calls per second no
     * matter how it handles (or ignores) the exception. A script that lets the
     * exception propagate is unaffected — it is exiting anyway.</p>
     */
    private static final long REVOKED_THROTTLE_MS = 250L;

    /**
     * Throws if the calling thread belongs to a revoked script. Called on the
     * RPC path; host threads (no tag) always pass.
     *
     * <p>Parks a revoked caller briefly before throwing — see
     * {@link #REVOKED_THROTTLE_MS}. Safe to block here: this runs before the
     * pipe lock is taken, so a revoked script cannot delay a healthy one.</p>
     */
    public void checkCaller() {
        String script = currentScript.get();
        if (script == null || !revoked.contains(script)) {
            return;
        }
        try {
            Thread.sleep(REVOKED_THROTTLE_MS);
        } catch (InterruptedException e) {
            // Preserve the flag for whatever the script does next; the
            // revocation still stands and is thrown below either way.
            Thread.currentThread().interrupt();
        }
        throw new ScriptRevokedException(script);
    }
}
