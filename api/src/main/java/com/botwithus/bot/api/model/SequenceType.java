package com.botwithus.bot.api.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Animation sequence definition from the game cache.
 *
 * @param id                   the sequence type ID
 * @param frameCount           the total number of frames
 * @param frameLengths         the duration of each frame
 * @param loopOffset           the frame offset to loop from, or {@code -1} for no looping
 * @param priority             the animation priority
 * @param offHand              the off-hand weapon override, or {@code -1} for none
 * @param mainHand             the main-hand weapon override, or {@code -1} for none
 * @param maxLoops             the maximum number of loops, or {@code 0} for unlimited
 * @param animatingPrecedence  the precedence when already animating
 * @param walkingPrecedence    the precedence when walking
 * @param replayMode           the replay mode
 * @param tweened              whether the animation uses tweening between frames
 * @param params               additional key-value parameters from the sequence definition
 * @see com.botwithus.bot.api.GameAPI#getSequenceType
 */
public record SequenceType(
        int id,
        int frameCount,
        List<Integer> frameLengths,
        int loopOffset,
        int priority,
        int offHand,
        int mainHand,
        int maxLoops,
        int animatingPrecedence,
        int walkingPrecedence,
        int replayMode,
        boolean tweened,
        Map<String, Object> params
) {

    /** The integer value of param {@code paramId}, or empty when the sequence has none. */
    public OptionalInt intParam(int paramId) {
        return ConfigValues.intParam(params, paramId);
    }

    /** The string value of param {@code paramId}, or empty when the sequence has none. */
    public Optional<String> stringParam(int paramId) {
        return ConfigValues.stringParam(params, paramId);
    }
}
