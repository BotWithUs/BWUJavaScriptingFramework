package com.botwithus.bot.api.debug;

/**
 * Fire-and-forget publisher for the agent's {@code script.context} broker topic.
 *
 * <p>Scripts (and the runtime that hosts them) call into this interface to expose
 * trace lines, lifecycle state changes, and freeform annotations to whoever is
 * watching the topic — primarily {@code NXTDebugger}. Calls are non-blocking:
 * the implementation enqueues onto a bounded ring and an internal worker thread
 * forwards each entry as an {@code _debug.publish} RPC. Overflows drop the
 * oldest entry rather than blocking the caller, mirroring the agent's per-pipe
 * push queue.</p>
 *
 * <p>When no debugger has subscribed, the agent's broker still accepts the
 * publish and discards it — the per-method cost is one round-trip on the pipe,
 * but the auto-tap fast-path keeps it off {@code rpc.tap}.</p>
 *
 * <p>The {@link #NOOP} instance is returned by {@link com.botwithus.bot.api.ScriptContext#getScriptContext()}
 * when no channel is wired (tests, disconnected runtimes). Callers should never
 * null-check the publisher; they always get a usable instance.</p>
 */
public interface ScriptContextPublisher {

    /** Lifecycle state notification with no further detail. */
    void state(String state);

    /** Lifecycle state notification with an optional human-readable detail. */
    void state(String state, String detail);

    /** Free-form trace line. {@code level} is "INFO" / "WARN" / "ERROR" / "DEBUG". */
    void trace(String level, String message);

    /**
     * Script-defined named annotation. {@code value} may be any msgpack-encodable
     * primitive ({@code String}, {@code Number}, {@code Boolean}) or nested
     * {@code Map} / {@code List}; the wire codec serializes it verbatim into the
     * push envelope so the debugger can render it without a per-key schema.
     */
    void annotation(String key, Object value);

    /**
     * No-op publisher. Returned when no channel has been wired into the script
     * context. All four methods are no-ops; never throws.
     */
    ScriptContextPublisher NOOP = new ScriptContextPublisher() {
        @Override public void state(String state) {}
        @Override public void state(String state, String detail) {}
        @Override public void trace(String level, String message) {}
        @Override public void annotation(String key, Object value) {}
    };
}
