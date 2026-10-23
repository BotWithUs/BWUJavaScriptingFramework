package com.botwithus.bot.api.constants;

public final class AnimationIds {
    private AnimationIds() {}

    public static final int IDLE = -1;
    /**
     * Woodcutting chop animation.
     *
     * <p>Measured live against a running client rather than taken from a table:
     * a level-47 Woodcutting character with a bronze hatchet showed
     * {@code 21191} on both tree types tested.</p>
     *
     * <ul>
     *   <li>Willow: 392 of 400 samples over 35 server ticks of continuous
     *       chopping (the remaining 8 were idle between cycles).</li>
     *   <li>Tree: 173 of 300 samples.</li>
     * </ul>
     *
     * <p>The previous value, {@code 21166}, was not observed in any sample of
     * either run. It is kept below as {@link #WOODCUTTING_LEGACY} rather than
     * deleted, because two tree types and one hatchet tier do not rule out the
     * animation being tier- or tree-specific. A consumer that must not miss a
     * chop should match either value; a consumer that wants the common case
     * should use this one.</p>
     */
    public static final int WOODCUTTING = 21191;

    /**
     * The value this constant held before it was measured. Never observed in
     * testing, but retained because the evidence above does not cover every
     * hatchet tier or tree.
     *
     * @see #WOODCUTTING
     */
    public static final int WOODCUTTING_LEGACY = 21166;

    public static final int MINING = 20317;
    public static final int FISHING = 21096;
    public static final int COOKING_FIRE = 16703;
    public static final int SMITHING = 898;
    public static final int FLETCHING = 24560;
    public static final int CRAFTING = 24560;
}
