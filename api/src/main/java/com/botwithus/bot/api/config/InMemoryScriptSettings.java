package com.botwithus.bot.api.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ScriptSettings#inMemory()}: a map, and a save that keeps nothing. */
final class InMemoryScriptSettings implements ScriptSettings {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }

    @Override
    public boolean remove(String key) {
        return values.remove(key) != null;
    }

    @Override
    public Map<String, String> asMap() {
        return Map.copyOf(values);
    }

    @Override
    public void save() {
    }
}
