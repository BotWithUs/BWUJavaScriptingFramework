package com.botwithus.bot.core.rpc;

/**
 * Outcome of looking for the pipe a dropped connection should reconnect to.
 *
 * <p>Reconnect is not simply "retry the name we started with". A producer pipe
 * is named {@code BotWithUs_<pid>}, and the whole connection is keyed on that
 * pid — the shared-memory snapshot mapping ({@code Local\nxt_snapshot_<pid>}),
 * the {@code connections} map key, the {@code ClientImpl}, and every entity
 * flyweight a running script is holding. So there are three materially
 * different answers, not two, and the controller must treat them differently:
 * one is recoverable, one is worth waiting for, and one must stop.</p>
 */
public sealed interface PipeResolution {

    /**
     * Our own pipe is listening again. Safe to reconnect: the agent is the
     * same process, so the shared-memory mapping the pump and the entity
     * flyweights are reading is still the live one.
     */
    record Found(String pipeName) implements PipeResolution {}

    /**
     * Our pipe is not listening yet, but the game process is still alive, so
     * the agent is expected back — an agent-side client drop that did not kill
     * the process. Keep retrying.
     */
    record NotYet(String detail) implements PipeResolution {}

    /**
     * Our pipe is gone for good. Retrying cannot succeed and adopting another
     * instance's pipe would be worse than failing — see
     * {@link SamePidPipeResolver} for why. Stop and tell the user.
     */
    record Gone(String detail) implements PipeResolution {}
}
