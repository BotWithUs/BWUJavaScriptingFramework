package com.botwithus.bot.core.worldwalker;

/**
 * Discriminator for the two kinds of plan {@link WwStep}: a routed Walk hop
 * toward a tile, or a Transition (object interaction / teleport / chain) at a
 * tile. Mirrors {@code ww::runtime::StepKind} and the {@code WW_STEP_KIND_*}
 * sentinels in {@code worldwalker_c.h}.
 */
public enum StepKind {

    /** Move toward {@code (targetX, targetY, plane)}; arrival ends the step. */
    WALK(WorldWalkerLayouts.WW_STEP_KIND_WALK),

    /**
     * At {@code (targetX, targetY, plane)}, invoke the transition at
     * {@link WwStep#transitionIndex()} and run its embedded chain.
     */
    TRANSITION(WorldWalkerLayouts.WW_STEP_KIND_TRANSITION);

    private final int wireValue;

    StepKind(int wireValue) {
        this.wireValue = wireValue;
    }

    /** Underlying C ABI byte value (0 = Walk, 1 = Transition). */
    public int wireValue() {
        return wireValue;
    }

    static StepKind fromWire(int wire) {
        return switch (wire) {
            case WorldWalkerLayouts.WW_STEP_KIND_WALK       -> WALK;
            case WorldWalkerLayouts.WW_STEP_KIND_TRANSITION -> TRANSITION;
            default -> throw new WorldWalkerException(
                    "Unknown WW_STEP_KIND wire value: " + wire);
        };
    }
}
