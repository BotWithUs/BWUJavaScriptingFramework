package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.model.GameAction;

/**
 * Rich wrapper for a player entity.
 *
 * <p>Obtain instances through {@link Players}:</p>
 * <pre>{@code
 * Players players = new Players(api);
 * Player nearest = players.nearest();
 * if (nearest != null) {
 *     System.out.println(nearest.name() + " combat: " + nearest.getCombatLevel());
 * }
 * }</pre>
 *
 * @see Players
 * @see EntityContext
 */
public class Player extends EntityContext {

    public Player(GameAPI api, Entity raw) {
        super(api, raw);
    }

    // ========================== Interaction ==========================

    /**
     * Interacts with this player using the given 1-based option index.
     *
     * @param optionIndex the 1-based option index (1–10)
     */
    public void interact(int optionIndex) {
        if (optionIndex < 1 || optionIndex >= ActionTypes.PLAYER_OPTIONS.length) {
            throw new IllegalArgumentException("Player option index out of range: " + optionIndex);
        }
        api.queueAction(new GameAction(ActionTypes.PLAYER_OPTIONS[optionIndex], raw.serverIndex(), 0, 0));
    }

    @Override
    public String toString() {
        return "Player{" + name()
                + " @" + tileX() + "," + tileY() + "," + plane() + "}";
    }
}
