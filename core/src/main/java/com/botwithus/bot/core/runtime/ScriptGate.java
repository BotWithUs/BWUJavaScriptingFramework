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

    /** {@code true} if the calling thread belongs to a revoked script. */
    public boolean isCallerRevoked() {
        String script = currentScript.get();
        return script != null && revoked.contains(script);
    }

    /**
     * Throws if the calling thread belongs to a revoked script. Called on the
     * RPC path; host threads (no tag) always pass.
     */
    public void checkCaller() {
        String script = currentScript.get();
        if (script != null && revoked.contains(script)) {
            throw new ScriptRevokedException(script);
        }
    }
}
