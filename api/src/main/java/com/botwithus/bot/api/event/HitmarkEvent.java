package com.botwithus.bot.api.event;

/**
 * Fired when a hitmark (damage splat) appears on a player or an NPC.
 *
 * <p>Decoded from wire event {@code 70}. The agent declares this event type and its body,
 * but whether it emits one is up to the agent build in use: a host on an agent that never
 * emits it simply sees no {@code HitmarkEvent}, never a wrong one.</p>
 *
 * @param targetServerIndex server index of the entity the hitmark is on
 * @param targetType        {@link #TARGET_PLAYER} or {@link #TARGET_NPC}
 * @param hitmarkType       the hitmark type id (its cache {@code hitmark} config); the
 *                          gameval index names it ({@code GamevalType.HITMARK})
 * @param damage            the number shown on the splat
 * @param cycle             the client cycle ({@code GameSnapshot.gameCycle()} units) the
 *                          hitmark was stamped with; compare it with other cycles, never
 *                          with {@code serverTick()} or {@code publishSeq()}
 * @param timestamp         event creation time in milliseconds since epoch
 */
public record HitmarkEvent(int targetServerIndex, int targetType, int hitmarkType, int damage,
                           int cycle, long timestamp) implements GameEvent {

    /** {@link #targetType()} of a hitmark on a player. */
    public static final int TARGET_PLAYER = 0;
    /** {@link #targetType()} of a hitmark on an NPC. */
    public static final int TARGET_NPC = 1;

    public HitmarkEvent(int targetServerIndex, int targetType, int hitmarkType, int damage,
                        int cycle) {
        this(targetServerIndex, targetType, hitmarkType, damage, cycle,
                System.currentTimeMillis());
    }

    /** True when the hitmark is on an NPC. */
    public boolean isOnNpc() {
        return targetType == TARGET_NPC;
    }

    /** True when the hitmark is on a player (the local player included). */
    public boolean isOnPlayer() {
        return targetType == TARGET_PLAYER;
    }
}
