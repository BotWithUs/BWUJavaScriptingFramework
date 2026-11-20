package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.gameval.GamevalEntry;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** A gameval index holding exactly the names a test puts in it. */
public final class MapGamevalIndex implements GamevalIndex {

    private final Map<String, Integer> ids = new HashMap<>();

    /** Adds {@code name} under {@code type} with {@code id}; returns this for chaining. */
    public MapGamevalIndex put(GamevalType type, String name, int id) {
        ids.put(type.name() + ":" + name, id);
        return this;
    }

    @Override
    public OptionalInt id(GamevalType type, String gameval) {
        Integer id = ids.get(type.name() + ":" + gameval);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
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
        return true;
    }

    @Override
    public Optional<String> meta(String key) {
        return Optional.empty();
    }
}
