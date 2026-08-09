package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.isc.SharedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SharedStateImpl implements SharedState {

    private static final Logger log = LoggerFactory.getLogger(SharedStateImpl.class);

    /**
     * Cap on distinct keys. The store is shared by every script on a connection
     * and keys are caller-supplied, so a script writing keys in a loop would
     * otherwise grow it until the host runs out of heap. Set far above any
     * plausible coordination use; reaching it means something is looping.
     */
    private static final int MAX_ENTRIES = 10_000;

    public SharedStateImpl() {}

    private final ConcurrentHashMap<String, Object> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        // Overwrites stay allowed at the cap — only new keys grow the map.
        if (store.size() >= MAX_ENTRIES && !store.containsKey(key)) {
            log.warn("SharedState is full at {} entries; dropping put for '{}'", MAX_ENTRIES, key);
            return;
        }
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
