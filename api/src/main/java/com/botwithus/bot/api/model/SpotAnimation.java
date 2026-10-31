package com.botwithus.bot.api.model;

/**
 * A world-anchored spot animation (graphic) currently playing on a tile.
 * Returned by {@link com.botwithus.bot.api.GameAPI#querySpotAnims()}.
 *
 * <p>Only tile-anchored spot animations are reported — graphics attached to
 * an NPC or player are not. There is no name for the id: the API has no
 * {@code SpotAnimType}, so match on {@link #id()}.</p>
 *
 * @param id    spot-anim (graphic) id
 * @param tileX absolute world tile X
 * @param tileY absolute world tile Y
 * @param plane 0..3, or {@link #UNKNOWN_PLANE} when the producer omits it
 */
public record SpotAnimation(int id, int tileX, int tileY, int plane) {

    /** Plane value used when the producer reply carries no {@code plane} field. */
    public static final int UNKNOWN_PLANE = -1;
}
