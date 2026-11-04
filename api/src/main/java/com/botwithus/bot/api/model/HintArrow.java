package com.botwithus.bot.api.model;

/**
 * A hint arrow the client holds, from {@link com.botwithus.bot.api.GameAPI#queryHintArrows()}.
 *
 * <p><b>Only a tile arrow has a position.</b> {@link #tileX()} and {@link #tileY()} mean something
 * for those; an NPC or player arrow is identified by {@link #targetIndex()}. Check
 * {@link #hasTile()} rather than the type. Live coverage so far is one tile arrow held in the tree:
 * slot arrows and NPC and player arrows have not been read live.</p>
 *
 * @param source      where the client holds it
 * @param slot        {@code 0..7} for a slot arrow, {@link #NO_VALUE} for a tree arrow
 * @param type        what it points at
 * @param rawType     the agent's {@code type} code, kept for a {@link HintArrowType#UNKNOWN} type
 * @param targetIndex server index for an NPC or player arrow, otherwise {@link #NO_VALUE}
 * @param plane       plane the arrow is on
 * @param tileX       tile X, or {@link #NO_VALUE} if the stored position is not a coordinate
 * @param tileY       tile Y, or {@link #NO_VALUE} if the stored position is not a coordinate
 * @param rangeTiles  the arrow's range in tiles
 */
public record HintArrow(
        Source source,
        int slot,
        HintArrowType type,
        int rawType,
        int targetIndex,
        int plane,
        int tileX,
        int tileY,
        int rangeTiles) {

    /** {@link #slot()}, {@link #targetIndex()}, {@link #tileX()} or {@link #tileY()} with no value. */
    public static final int NO_VALUE = -1;

    /** Where the client holds an arrow. */
    public enum Source {
        /** One of the hint-arrow manager's 8 slots. */
        SLOT,
        /** The hint-arrow manager's owner tree. */
        TREE,
        /** A spelling this host does not know. */
        UNKNOWN;

        private static final String SLOT_WIRE_NAME = "slot";
        private static final String TREE_WIRE_NAME = "tree";

        /** The source for the agent's {@code source} string, matched exactly. */
        public static Source fromWireName(String name) {
            return switch (name) {
                case SLOT_WIRE_NAME -> SLOT;
                case TREE_WIRE_NAME -> TREE;
                default -> UNKNOWN;
            };
        }
    }

    /** True when this is a tile arrow whose stored position is a coordinate. */
    public boolean hasTile() {
        return type.isTile() && tileX >= 0 && tileY >= 0;
    }

    /** Chebyshev distance from this arrow's tile. Only meaningful when {@link #hasTile()} is true. */
    public int distanceTo(int x, int y) {
        return Math.max(Math.abs(tileX - x), Math.abs(tileY - y));
    }
}
