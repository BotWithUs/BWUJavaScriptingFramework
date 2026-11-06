package com.botwithus.bot.core.shm;

/**
 * One event-ring {@code action refused} (type {@link Layout#EVT_ACTION_REFUSED}): the agent
 * dropped a queued action undispatched because it could not give it the click a user would have
 * made. The action is as it was queued; {@code reason}, {@code anchorCase} and {@code heldMs} say
 * why and for how long it was held (nxt-library {@code ActionRefusedBody}, PUBLIC_PROTOCOL
 * §6.3.2).
 *
 * <p>Core-internal: the host reacts to it (a walk click past the minimap's reach is re-clicked
 * shorter), and scripts do not see it on the event bus.</p>
 *
 * @param actionId   the refused action's id ({@code ActionTypes})
 * @param param1     the action's first parameter, as queued
 * @param param2     the second parameter; a walk's tile x
 * @param param3     the third parameter; a walk's tile y
 * @param reason     one of the {@code REASON_*} values
 * @param anchorCase what the hold waited for: one of the {@code ANCHOR_*} values
 * @param heldMs     how long the action was held before it was refused
 */
public record ActionRefusal(int actionId, int param1, int param2, int param3,
                            int reason, int anchorCase, int heldMs) {

    /** At once: the target is not loaded or has no point a click could land on. */
    public static final int REASON_NO_TARGET      = 1;
    /** The target existed but did not become clickable within the anchor timeout. */
    public static final int REASON_ANCHOR_TIMEOUT = 2;
    /** The component stayed hidden and no opener is known for it. */
    public static final int REASON_NO_OPENER      = 3;

    /** No hold: the action was refused at once. */
    public static final int ANCHOR_NONE   = 0;
    /** The hold waited on a scroll. */
    public static final int ANCHOR_SCROLL = 1;
    /** The hold waited on a window or tab to open. */
    public static final int ANCHOR_OPEN   = 2;
    /** The hold waited on the camera: the target was behind it. */
    public static final int ANCHOR_CAMERA = 3;
    /** The hold waited out a transient miss. */
    public static final int ANCHOR_WAIT   = 4;

    /** Wire size of the body: seven 4-byte fields. */
    public static final int BODY_BYTES = 28;
}
