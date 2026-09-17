package com.botwithus.bot.api.event;

/**
 * Fired when a new spot animation (graphic) starts playing. The producer emits
 * one event per newly-started spot anim (edge-triggered — it does not re-fire
 * while a spot anim persists across ticks). Two flavours share this event:
 *
 * <ul>
 *   <li><b>Entity-attached</b> ({@code targetType} {@code 0 = player} /
 *       {@code 1 = npc}): the graphic plays on an NPC/player, walked from that
 *       entity's graph-node children. {@code targetServerIndex} is the entity's
 *       server slot; {@code tileX}/{@code tileY}/{@code plane} are its tile when
 *       the spot anim started. An entity can play several at once; the per-entity
 *       snapshot field surfaces only the first, so this event is the way to
 *       observe every one.</li>
 *   <li><b>World/static</b> ({@code targetType == 2}, {@code targetServerIndex ==
 *       -1}): the graphic plays on a tile with no entity (spell splashes, AoE
 *       effects, ground sparkles), walked from the world {@code SpotAnimList}.
 *       {@code tileX}/{@code tileY} are the graphic's world tile; {@code plane} is
 *       the local player's plane (the active render level). Use
 *       {@link #isWorldAnchored()} to distinguish these.</li>
 * </ul>
 *
 * @param targetServerIndex entity server slot, or {@code -1} for world/static
 * @param targetType        {@code 0 = player}, {@code 1 = npc}, {@code 2 = world}
 * @param spotAnimId        the spot anim (graphic) id
 * @param tileX             tile X (entity's, or the world graphic's) when it started
 * @param tileY             tile Y (entity's, or the world graphic's) when it started
 * @param plane             plane (0..3)
 * @param timestamp         event creation time in milliseconds since epoch
 */
public record SpotAnimEvent(int targetServerIndex, int targetType, int spotAnimId,
                            int tileX, int tileY, int plane, long timestamp)
        implements GameEvent {

    public SpotAnimEvent(int targetServerIndex, int targetType, int spotAnimId,
                         int tileX, int tileY, int plane) {
        this(targetServerIndex, targetType, spotAnimId, tileX, tileY, plane,
                System.currentTimeMillis());
    }

    /**
     * True for a world/static spot anim — one playing on a tile with no entity
     * ({@code targetServerIndex == -1}, equivalently {@code targetType == 2}). For
     * these, {@code tileX}/{@code tileY} carry the graphic's world tile.
     */
    public boolean isWorldAnchored() {
        return targetServerIndex == -1;
    }
}
