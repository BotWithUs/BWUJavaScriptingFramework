package com.botwithus.bot.core.worldwalker;

/**
 * One avoid area: an inclusive tile rectangle on one plane. Mirrors the C ABI
 * {@code WwArea} (20 bytes).
 *
 * @param plane 0..3, or {@link #ALL_PLANES}
 * @param minX  inclusive
 * @param minY  inclusive
 * @param maxX  inclusive, {@code >= minX}
 * @param maxY  inclusive, {@code >= minY}
 */
public record WwArea(int plane, int minX, int minY, int maxX, int maxY) {

    /** {@code WW_AREA_ALL_PLANES}. */
    public static final int ALL_PLANES = -1;

    /** True when the tile is inside the rectangle, on its plane. */
    public boolean contains(int x, int y, int tilePlane) {
        boolean isOnPlane = plane == ALL_PLANES || plane == tilePlane;
        return isOnPlane && x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
