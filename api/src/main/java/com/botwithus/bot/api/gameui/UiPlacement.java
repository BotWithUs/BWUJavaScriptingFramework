package com.botwithus.bot.api.gameui;

import java.util.OptionalInt;

/**
 * The key a new component takes under its parent: {@code (category, dynamic)}.
 *
 * <p>Usually left to the agent ({@link #auto()}): the category defaults to the
 * parent's own, or {@code 1} when that is {@code 0}, and the dynamic id to the
 * parent's next free one. An occupied key is refused, never replaced.</p>
 *
 * <p>Category {@code 0} is unrepresentable here. Agent components never use it,
 * and the host never sends it.</p>
 *
 * @param category explicit category, {@link UiLimits#MIN_CATEGORY}..{@link UiLimits#MAX_CATEGORY}
 * @param dynamic  explicit dynamic id, {@code 0}..{@link UiLimits#MAX_DYNAMIC}
 */
public record UiPlacement(OptionalInt category, OptionalInt dynamic) {

    private static final UiPlacement AUTO = new UiPlacement(OptionalInt.empty(), OptionalInt.empty());

    public UiPlacement {
        category = category != null ? category : OptionalInt.empty();
        dynamic = dynamic != null ? dynamic : OptionalInt.empty();
        if (category.isPresent() && (category.getAsInt() < UiLimits.MIN_CATEGORY
                || category.getAsInt() > UiLimits.MAX_CATEGORY)) {
            throw new IllegalArgumentException("category must be " + UiLimits.MIN_CATEGORY
                    + ".." + UiLimits.MAX_CATEGORY + ", was " + category.getAsInt());
        }
        if (dynamic.isPresent() && (dynamic.getAsInt() < 0 || dynamic.getAsInt() > UiLimits.MAX_DYNAMIC)) {
            throw new IllegalArgumentException("dynamic must be 0.." + UiLimits.MAX_DYNAMIC
                    + ", was " + dynamic.getAsInt());
        }
    }

    /** Let the agent choose both halves of the key. */
    public static UiPlacement auto() {
        return AUTO;
    }

    /** An explicit category; the agent picks the dynamic id. */
    public static UiPlacement category(int category) {
        return new UiPlacement(OptionalInt.of(category), OptionalInt.empty());
    }

    /** An explicit key. */
    public static UiPlacement key(int category, int dynamic) {
        return new UiPlacement(OptionalInt.of(category), OptionalInt.of(dynamic));
    }
}
