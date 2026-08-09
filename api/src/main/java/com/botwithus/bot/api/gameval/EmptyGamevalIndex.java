package com.botwithus.bot.api.gameval;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Null-object {@link GamevalIndex}: nothing resolves, nothing throws on
 * construction. Returned by {@link GamevalIndex#empty()} when no
 * {@code gameval.sqlite} is deployed, so scripts see an index that answers
 * "unknown" rather than a {@code null} that answers with an NPE.
 *
 * <p>Stateless and immutable, so instances are interchangeable.</p>
 */
final class EmptyGamevalIndex implements GamevalIndex {

    @Override
    public OptionalInt id(GamevalType type, String gameval) {
        return OptionalInt.empty();
    }

    @Override
    public Optional<String> gameval(GamevalType type, int id) {
        return Optional.empty();
    }

    @Override
    public List<GamevalEntry> startingWith(GamevalType type, String prefix, int limit) {
        return List.of();
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Optional<String> meta(String key) {
        return Optional.empty();
    }
}
