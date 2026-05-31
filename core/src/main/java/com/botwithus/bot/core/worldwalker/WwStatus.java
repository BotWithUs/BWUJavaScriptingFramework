package com.botwithus.bot.core.worldwalker;

/**
 * Terminal status of a single {@link WorldWalker#runExecutor} call. Mirrors
 * {@code ww::exec::WwStatus} and the {@code WW_STATUS_*} sentinels in
 * {@code worldwalker_c.h}.
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
    CANCELLED(WorldWalkerLayouts.WW_STATUS_CANCELLED);

    private final int wireValue;

    WwStatus(int wireValue) {
        this.wireValue = wireValue;
    }

    /** Underlying C ABI {@code int32_t} (0 = Arrived, 1 = Failed, 2 = Cancelled). */
    public int wireValue() {
        return wireValue;
    }

    static WwStatus fromWire(int wire) {
        return switch (wire) {
            case WorldWalkerLayouts.WW_STATUS_ARRIVED   -> ARRIVED;
            case WorldWalkerLayouts.WW_STATUS_FAILED    -> FAILED;
            case WorldWalkerLayouts.WW_STATUS_CANCELLED -> CANCELLED;
            default -> throw new WorldWalkerException(
                    "Unknown WW_STATUS wire value: " + wire);
        };
    }
}
