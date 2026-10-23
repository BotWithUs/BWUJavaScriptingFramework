package com.botwithus.bot.cli;

import java.util.OptionalInt;

/**
 * What a connected client was last seen doing in the game, read together so a
 * reader never pairs one refresh's state with another's world.
 *
 * @param state    where the client is; {@link GameState#UNKNOWN} until the first
 *                 successful refresh
 * @param world    the world the client is in; empty unless {@code state} is
 *                 {@link GameState#IN_GAME} and the agent reported a world
 * @param isMember whether the account is a member. The agent can only tell once
 *                 the client is in a world, so this is {@code false} before then.
 */
public record GameStatus(GameState state, OptionalInt world, boolean isMember) {

    /** Nothing known yet. */
    public static final GameStatus UNKNOWN = new GameStatus(GameState.UNKNOWN, OptionalInt.empty(), false);

    public GameStatus {
        if (state == null || world == null) {
            throw new IllegalArgumentException("state and world are required");
        }
    }

    /**
     * This status moved to {@code next}. A world and membership are only
     * meaningful in a world, so leaving one drops them; staying in one keeps them
     * until the next full refresh says otherwise.
     */
    public GameStatus withState(GameState next) {
        if (next == GameState.IN_GAME) {
            return new GameStatus(next, world, isMember);
        }
        return new GameStatus(next, OptionalInt.empty(), false);
    }
}
