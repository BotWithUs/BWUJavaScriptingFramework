package com.botwithus.bot.core.worldwalker;

/**
 * Discriminant for a transition execution-chain step. Wire values mirror
 * {@code ww::data::ChainStepKind} in {@code WorldWalker/src/data/Transitions.h}
 * and are passed as the first argument of {@link WwCallbacks#runChainStep}.
 *
 * <p>The executor resolves {@link #WAIT} and {@link #WAIT_INTERFACE} itself, so
 * only {@link #CLICK}, {@link #DIALOGUE_SELECT}, and {@link #CLICK_ITEM} ever
 * reach the host callback.</p>
 */
public enum ChainStepKind {

    /** Generic queued action: {@code a=actionId, b..d=param1..3}. */
    CLICK(0),
    /** Sleep {@code a} game ticks (executor-side; never reaches the host). */
    WAIT(1),
    /** Block until interface {@code a} is open (executor-side; never reaches the host). */
    WAIT_INTERFACE(2),
    /** Select option {@code b} in dialogue interface {@code a} (host-resolved). */
    DIALOGUE_SELECT(3),
    /** Click a teleport item, worn or carried (host-resolved, dual-variant). */
    CLICK_ITEM(4);

    private final int wire;

    ChainStepKind(int wire) {
        this.wire = wire;
    }

    /** The on-the-wire integer value matching the C++ enum. */
    public int wire() {
        return wire;
    }

    /**
     * Map a wire value to its enum constant.
     *
     * @throws IllegalArgumentException if {@code wire} matches no known kind —
     *         a producer/consumer drift that must fail loud, not silently no-op.
     */
    public static ChainStepKind fromWire(int wire) {
        return switch (wire) {
            case 0 -> CLICK;
            case 1 -> WAIT;
            case 2 -> WAIT_INTERFACE;
            case 3 -> DIALOGUE_SELECT;
            case 4 -> CLICK_ITEM;
            default -> throw new IllegalArgumentException("unknown ChainStepKind wire value: " + wire);
        };
    }
}
