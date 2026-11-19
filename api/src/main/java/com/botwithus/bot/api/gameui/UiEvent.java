package com.botwithus.bot.api.gameui;

import java.util.Optional;

/**
 * Something the agent announced about a handle, on the {@code ui.event} topic.
 *
 * <p>Delivered only to the script that owns the handle (for a Jagex handle, every
 * script on the connection that looked it up). The hook and reason are kept as
 * the agent's strings rather than enums so a hook or reason the agent adds later
 * reaches a script unchanged instead of being dropped by an older host; compare
 * them against the constants here.</p>
 *
 * <p>Today the only hook is {@link #HOOK_DESTROYED}: the handle no longer works
 * and every call on it fails with {@code unknown handle}. It is not sent for a
 * script's own {@code destroy()}.</p>
 *
 * @param handle the handle the event is about
 * @param hook   what happened
 * @param reason why, when the hook carries one
 */
public record UiEvent(long handle, String hook, Optional<String> reason) {

    /** The handle's component is gone. */
    public static final String HOOK_DESTROYED = "destroyed";

    /** Its parent no longer resolves, for example a toplevel torn down on logout. */
    public static final String REASON_PARENT_GONE = "parent_gone";

    /** The component itself is gone or was replaced. */
    public static final String REASON_REMOVED = "removed";

    /** The agent destroyed it because it inherited server op flags. */
    public static final String REASON_SERVER_EVENTS = "server_events";

    /** The op-flag check could not be read, and the guard failed closed. */
    public static final String REASON_INTERFACE_UNREADABLE = "interface_unreadable";

    public UiEvent {
        if (hook == null) {
            throw new IllegalArgumentException("hook");
        }
        reason = reason != null ? reason : Optional.empty();
    }

    /** Whether this says the handle is gone. */
    public boolean isDestroyed() {
        return HOOK_DESTROYED.equals(hook);
    }
}
