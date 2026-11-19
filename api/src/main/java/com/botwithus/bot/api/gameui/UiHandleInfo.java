package com.botwithus.bot.api.gameui;

/**
 * What the agent reports about a plugin-UI handle.
 *
 * @param handle        opaque id; never a pointer, never reused, never {@code 0}
 * @param type          the component's type
 * @param iface         interface id of the static component (a dynamic child reports
 *                      its static ancestor's ids)
 * @param comp          component id of that static component
 * @param key           the dynamic child key, or {@link #STATIC_KEY} for a static one
 * @param parent        the parent's handle; {@code 0} for a Jagex component
 * @param isJagex       a component the game made: usable only as a parent and with
 *                      {@code ui.get}
 * @param isQuarantined a removal that did not take; the agent retries it every tick
 *                      and refuses every verb on it but {@code ui.list}
 */
public record UiHandleInfo(long handle, UiType type, int iface, int comp, int key, long parent,
                           boolean isJagex, boolean isQuarantined) {

    /** The {@link #key()} of a static component. */
    public static final int STATIC_KEY = -1;

    public UiHandleInfo {
        if (handle == 0) {
            throw new IllegalArgumentException("handle 0 is never valid");
        }
        if (type == null) {
            throw new IllegalArgumentException("type");
        }
    }
}
