package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;

/**
 * Rich wrapper around a snapshot {@link com.botwithus.bot.api.snapshot.Player}
 * record. Players don't have a per-id type definition the way NPCs do —
 * what would be on {@code NpcType} (name, options, ...) is per-instance and
 * lives in the snapshot or arrives as packets — so this wrapper is thinner
 * than {@link Npc}.
 */
public final class Player implements EntityContext {

    private final GameAPI api;
    private final com.botwithus.bot.api.snapshot.Player raw;

    Player(GameAPI api, com.botwithus.bot.api.snapshot.Player raw) {
        this.api = api;
        this.raw = raw;
    }

    public com.botwithus.bot.api.snapshot.Player raw() { return raw; }

    public int serverIndex()    { return raw.serverIndex(); }
    public int combatLevel()    { return raw.combatLevel(); }
    public int followingIndex() { return raw.followingIndex(); }
    public int animationId()    { return raw.animationId(); }
    public int stanceId()       { return raw.stanceId(); }
    public boolean isMoving()   { return raw.isMoving(); }

    @Override public int tileX() { return raw.tileX(); }
    @Override public int tileY() { return raw.tileY(); }
    @Override public int plane() { return raw.plane(); }

    /**
     * Queue an action against this player by 1-based option index.
     *
     * @throws IllegalArgumentException for {@code optionIndex} outside [1, 10]
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.PLAYER_OPTIONS.length) {
            throw new IllegalArgumentException("Player option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(
                ActionTypes.PLAYER_OPTIONS[optionIndex],
                serverIndex(), 0, 0));
    }

    @Override
    public String toString() {
        return "Player{idx=" + serverIndex()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
