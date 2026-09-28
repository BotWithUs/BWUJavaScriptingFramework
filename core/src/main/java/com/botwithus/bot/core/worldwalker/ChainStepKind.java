package com.botwithus.bot.core.worldwalker;

/**
 * Discriminant for a transition execution-chain step. Wire values mirror
 * {@code ww::data::ChainStepKind} in {@code WorldWalker/src/data/Transitions.h}
 * and are passed as the first argument of {@link WwCallbacks#runChainStep}.
 *
 * <p>The executor resolves {@link #WAIT} and {@link #WAIT_INTERFACE} itself, so
 * only {@link #CLICK}, {@link #DIALOGUE_SELECT}, {@link #CLICK_ITEM},
 * {@link #DIALOGUE_ANSWER} and {@link #CLICK_NPC} ever reach the host callback.</p>
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
    CLICK_ITEM(4),
    /**
     * Pick the first open dialogue option whose text contains an answer
     * (host-resolved). Never baked into a chain: the executor sends it itself.
     * {@code a..i} carry the answer text; {@link DialogueAnswerText} decodes it.
     */
    DIALOGUE_ANSWER(5),
    /**
     * Click the nearest live NPC whose type id is in {@code [f, g]}, on plane
     * {@code d}, within Chebyshev {@code e} of {@code (b, c)}, with 0-based
     * option {@code a} (host-resolved). The origin of a transition with no loc.
     */
    CLICK_NPC(6);

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
            case 5 -> DIALOGUE_ANSWER;
            case 6 -> CLICK_NPC;
            default -> throw new IllegalArgumentException("unknown ChainStepKind wire value: " + wire);
        };
    }
}
