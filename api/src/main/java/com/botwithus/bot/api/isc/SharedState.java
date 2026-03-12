package com.botwithus.bot.api.isc;

import java.util.Map;

/**
 * Thread-safe key-value store for sharing state between scripts.
 */
public interface SharedState {

    /**
     * Stores a value under the given key, replacing any existing value.
     *
     * @param key   the key to store the value under
     * @param value the value to store
     */
    void put(String key, Object value);

    /**
     * Retrieves the value stored under the given key.
     *
     * @param key the key to look up
     * @return the stored value, or {@code null} if not present
     */
    Object get(String key);

    /**
     * Retrieves and casts the value stored under the given key.
     *
     * @param <T>  the expected value type
     * @param key  the key to look up
     * @param type the expected class of the value
     * @return the stored value cast to {@code T}, or {@code null} if not present or not an instance of {@code type}
     */
    @SuppressWarnings("unchecked")
    default <T> T get(String key, Class<T> type) {
        Object value = get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    /**
     * Removes the value stored under the given key.
     *
     * @param key the key to remove
     * @return the previously stored value, or {@code null} if not present
     */
    Object remove(String key);

    /**
     * Checks whether a value is stored under the given key.
     *
     * @param key the key to check
     * @return {@code true} if a value exists for the key
     */
    boolean containsKey(String key);

    /**
     * Returns an immutable snapshot of all key-value pairs currently stored.
     *
     * @return an unmodifiable map of all entries
     */
    Map<String, Object> snapshot();

    /**
     * Removes all stored key-value pairs.
     */
    void clear();
}
