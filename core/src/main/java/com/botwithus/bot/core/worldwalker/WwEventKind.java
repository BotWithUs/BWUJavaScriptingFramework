package com.botwithus.bot.core.worldwalker;

/**
 * Progress-event discriminator for {@link WwCallbacks#onEvent}. Mirrors
 * {@code ww::exec::WwEventKind} and the {@code WW_EVENT_*} sentinels in
 * {@code worldwalker_c.h}.
 *
 * <p>The C header reserves room for future event kinds and instructs hosts to
 * treat an unknown {@code kind} value as "ignore". This enum honours that
 * contract via {@link #UNKNOWN}: any wire value the Java side doesn't
 * recognise decodes to {@code UNKNOWN} (with the raw value preserved on the
 * containing {@link WwEvent} for debugging) rather than throwing.</p>
 */
public enum WwEventKind {

    /** Executor advanced to a new {@link WwStep} in the current plan. */
    STEP_ADVANCED(WorldWalkerLayouts.WW_EVENT_STEP_ADVANCED),

    /** Approaching a transition's interact tile. */
    WALKING_TO_INTERACT(WorldWalkerLayouts.WW_EVENT_WALKING_TO_INTERACT),

    /** Executor began running a global teleport's chain. */
    TELEPORT_INITIATED(WorldWalkerLayouts.WW_EVENT_TELEPORT_INITIATED),

    /** Stuck deadline elapsed on the current step. */
    STUCK(WorldWalkerLayouts.WW_EVENT_STUCK),

    /** Re-invoking the planner in-process. */
    REPLAN_STARTED(WorldWalkerLayouts.WW_EVENT_REPLAN_STARTED),

    /** Reached the goal's acceptance set. Terminal. */
    ARRIVED(WorldWalkerLayouts.WW_EVENT_ARRIVED),

    /** Unrecoverable error. Terminal. */
    FAILED(WorldWalkerLayouts.WW_EVENT_FAILED),

    /**
     * Forward-compat sentinel for any {@code kind} the native side may grow
     * after this Java was built. Hosts should ignore these (the C header
     * specifies "ignore unknown kinds"); the raw integer is preserved on
     * {@link WwEvent#rawKind()} for diagnostic logging.
     */
    UNKNOWN(-1);

    private final int wireValue;

    WwEventKind(int wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Underlying C ABI {@code int32_t}, or {@code -1} for {@link #UNKNOWN}.
     * Never use this for an outbound transmission of an unknown kind — the
     * sentinel is host-internal only.
     */
    public int wireValue() {
        return wireValue;
    }

    static WwEventKind fromWire(int wire) {
        return switch (wire) {
            case WorldWalkerLayouts.WW_EVENT_STEP_ADVANCED       -> STEP_ADVANCED;
            case WorldWalkerLayouts.WW_EVENT_WALKING_TO_INTERACT -> WALKING_TO_INTERACT;
            case WorldWalkerLayouts.WW_EVENT_TELEPORT_INITIATED  -> TELEPORT_INITIATED;
            case WorldWalkerLayouts.WW_EVENT_STUCK               -> STUCK;
            case WorldWalkerLayouts.WW_EVENT_REPLAN_STARTED      -> REPLAN_STARTED;
            case WorldWalkerLayouts.WW_EVENT_ARRIVED             -> ARRIVED;
            case WorldWalkerLayouts.WW_EVENT_FAILED              -> FAILED;
            default                                              -> UNKNOWN;
        };
    }
}
