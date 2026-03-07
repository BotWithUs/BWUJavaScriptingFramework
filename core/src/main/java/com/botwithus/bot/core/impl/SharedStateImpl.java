package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.SharedState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SharedStateImpl implements SharedState {

    private final ConcurrentHashMap<String, Object> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Object value) {
        store.put(key, value);
    }

    @Override
    public Object get(String key) {
        return store.get(key);
    }

    @Override
    public Object remove(String key) {
        return store.remove(key);
    }

    @Override
    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    @Override
    public Map<String, Object> snapshot() {
        return Map.copyOf(store);
    }

    @Override
    public void clear() {
        store.clear();
    }
}
