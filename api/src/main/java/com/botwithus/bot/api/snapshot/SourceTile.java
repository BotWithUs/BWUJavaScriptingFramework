package com.botwithus.bot.api.snapshot;

/**
 * The static-map tile an instance tile was copied from (v19+).
 *
 * <p>Returned by {@link DynamicRegion#sourceOf(int, int, int)}. The coordinates
 * are <b>absolute world tiles in the source region</b> — the same coordinate
 * space as {@link Npc#tileX()} or a baked navigation grid, not an offset and not
 * a scene-local position. That is the whole point of the resolver: a tile inside
 * a player-owned house resolves to the tile in the house template region that
 * supplied its terrain, collision and scenery.</p>
 *
 * <p>{@link #rotation()} is the chunk's rotation, {@code 0..3} in 90-degree
 * steps, and it is not merely informational: anything directional read out of
 * the source tile has to be rotated by it before being believed in the
 * instance. Wall and door collision bits are the classic case, and a
 * {@link Location}'s own rotation composes with it via
 * {@link DynamicRegion#rotateLocRotation(int, int)}. Copying raw directional
 * flags without applying this is the standard way to get an instance's
 * collision subtly wrong.</p>
 *
 * @param tileX    absolute world tile X in the source region
 * @param tileY    absolute world tile Y in the source region
 * @param plane    source plane, {@code 0..3} — need not equal the plane asked for
 * @param rotation chunk rotation applied to the copy, {@code 0..3}
 */
public record SourceTile(
        int tileX,
        int tileY,
        int plane,
        int rotation
) {
}
