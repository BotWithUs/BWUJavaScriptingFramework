package com.botwithus.bot.core.worldwalker;

/**
 * Terminal status of a single {@link WorldWalker#runExecutor} call. Mirrors
 * {@code ww::exec::WwStatus} and the {@code WW_STATUS_*} sentinels in
 * {@code worldwalker_c.h}.
 *
 * <p>Forward-compatibility: any wire value the Java side doesn't recognise
 * decodes to {@link #UNKNOWN} rather than throwing, so a freshly-built native
 * library introducing a new status code doesn't break the host. Treat
 * {@code UNKNOWN} as a non-terminal "ignore" — pessimistic callers can map it
 * to {@link #FAILED} themselves if needed.</p>
 */
public enum WwStatus {

    /** Reached the goal's acceptance set. */
    ARRIVED(WorldWalkerLayouts.WW_STATUS_ARRIVED),

    /**
     * Hit an unrecoverable error — invalid arguments, an internal exception,
     * or a callback that threw (see {@link WorldWalkerException}). Inspect
     * {@code ww_last_error()} via {@link WorldWalker} for a message.
     */
    FAILED(WorldWalkerLayouts.WW_STATUS_FAILED),

    /**
     * {@link WwCallbacks#shouldCancel()} signalled cancel and the executor
     * aborted at the next safe point.
     */
    CANCELLED(WorldWalkerLayouts.WW_STATUS_CANCELLED),

    /**
     * Forward-compat sentinel for any {@code rc} the native side may grow
     * after this Java was built. The wire integer is host-internal only.
     */
    UNKNOWN(-1);

    private final int wireValue;

    WwStatus(int wireValue) {
        this.wireValue = wireValue;
    }

    /** Underlying C ABI {@code int32_t}, or {@code -1} for {@link #UNKNOWN}. */
    public int wireValue() {
        return wireValue;
    }

    static WwStatus fromWire(int wire) {
        return switch (wire) {
            case WorldWalkerLayouts.WW_STATUS_ARRIVED   -> ARRIVED;
            case WorldWalkerLayouts.WW_STATUS_FAILED    -> FAILED;
            case WorldWalkerLayouts.WW_STATUS_CANCELLED -> CANCELLED;
            default                                     -> UNKNOWN;
        };
    }
}
