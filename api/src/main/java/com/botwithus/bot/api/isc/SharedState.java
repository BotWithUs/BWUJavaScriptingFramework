package com.botwithus.bot.api.isc;

import java.util.Map;

/**
 * Thread-safe key-value store for sharing state between scripts.
 */
public interface SharedState {

    void put(String key, Object value);

    Object get(String key);

    @SuppressWarnings("unchecked")
    default <T> T get(String key, Class<T> type) {
        Object value = get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    Object remove(String key);

    boolean containsKey(String key);

    Map<String, Object> snapshot();

    void clear();
}
