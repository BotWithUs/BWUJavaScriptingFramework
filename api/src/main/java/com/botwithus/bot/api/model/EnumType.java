package com.botwithus.bot.api.model;

import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Enum (key-value mapping) definition from the game cache.
 * Enums map input keys to output values (e.g., skill IDs to skill names).
 *
 * @param id            the enum type ID
 * @param inputTypeId   the input key type identifier
 * @param outputTypeId  the output value type identifier
 * @param intDefault    the default integer value when a key is not found
 * @param stringDefault the default string value when a key is not found
 * @param entryCount    the total number of entries in this enum
 * @param entries       the key-value mapping entries, keyed by the input in decimal
 * @see com.botwithus.bot.api.GameAPI#getEnumType
 */
public record EnumType(
        int id,
        int inputTypeId,
        int outputTypeId,
        int intDefault,
        String stringDefault,
        int entryCount,
        Map<String, Object> entries
) {

    /**
     * The integer output for {@code key}, or empty when the enum has no entry for it.
     * Unlike the game, this does not substitute {@link #intDefault()}: a script asking
     * whether a key exists should not get the default back as if it did.
     */
    public OptionalInt intValue(int key) {
        return ConfigValues.asInt(entries.get(Integer.toString(key)));
    }

    /**
     * Every entry whose key and output are both integers, ordered by key. Entries of any
     * other shape are left out.
     */
    public Map<Integer, Integer> intEntries() {
        Map<Integer, Integer> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            OptionalInt value = ConfigValues.asInt(entry.getValue());
            OptionalInt key = parseKey(entry.getKey());
            if (value.isPresent() && key.isPresent()) {
                result.put(key.getAsInt(), value.getAsInt());
            }
        }
        return result;
    }

    private static OptionalInt parseKey(String key) {
        try {
            return OptionalInt.of(Integer.parseInt(key));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
