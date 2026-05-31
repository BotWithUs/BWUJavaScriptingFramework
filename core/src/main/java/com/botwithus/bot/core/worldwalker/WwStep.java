package com.botwithus.bot.core.worldwalker;

/**
 * One emitted action of an assembled {@link WwPathResult}. Mirrors the C ABI
 * {@code WwStep} (16 bytes); decoded by {@link WorldWalker#query} and copied
 * out before the native buffer is freed.
 *
 * @param kind             Walk or Transition discriminator
 * @param plane            destination plane index (0–3)
 * @param targetX          destination tile X
 * @param targetY          destination tile Y
 * @param transitionIndex  index into the artifact's transition table for a
 *                         {@link StepKind#TRANSITION} step, or {@code 0xFFFFFFFF}
 *                         (decoded here as the {@code long} {@value WALK_TRANSITION_SENTINEL})
 *                         for a {@link StepKind#WALK} step
 */
public record WwStep(StepKind kind, int plane, int targetX, int targetY, long transitionIndex) {

    /** Value that {@link #transitionIndex()} carries on a Walk step ({@code UINT32_MAX}). */
    public static final long WALK_TRANSITION_SENTINEL = 0xFFFF_FFFFL;
}
