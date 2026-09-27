package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.GameStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * A pipe the last scan saw, with what the scan's probe read from it.
 *
 * @param pipe    the pipe name
 * @param account the account's display name, if the probe read one
 * @param game    game state, world and membership, as far as the probe could tell
 */
public record FoundPipe(String pipe, Optional<String> account, GameStatus game) {

    public FoundPipe {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(game, "game");
    }
}
