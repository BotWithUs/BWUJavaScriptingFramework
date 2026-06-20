package com.botwithus.bot.api.event;

/**
 * Fired when a new spot animation (graphic) starts playing on an entity. The
 * producer walks each NPC/player's graph-node children every tick and emits one
 * event per newly-started spot anim (edge-triggered — it does not re-fire while
 * a spot anim persists across ticks).
 *
 * <p>{@code targetServerIndex} is the server slot of the entity the graphic is
 * anchored to, with {@code targetType} distinguishing it ({@code 0 = player},
 * {@code 1 = npc}); {@code -1} would indicate a world-anchored spot anim, which
 * this per-entity path never emits. {@code tileX}/{@code tileY}/{@code plane} are
 * the entity's tile when the spot anim started.</p>
 *
 * <p>An entity can play several spot anims at once; the per-entity snapshot field
 * surfaces only the first, so this event is the way to observe every one.</p>
 *
 * @param targetServerIndex server slot of the entity the spot anim plays on
 * @param targetType        {@code 0 = player}, {@code 1 = npc}
 * @param spotAnimId        the spot anim (graphic) id
 * @param tileX             entity tile X when the spot anim started
 * @param tileY             entity tile Y when the spot anim started
 * @param plane             entity plane (0..3)
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
}
