package com.botwithus.bot.api.model;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Struct definition from the game cache.
 * Structs are parameter bags containing key-value pairs of int or string values.
 *
 * @param id     the struct type ID
 * @param params the key-value parameter pairs, keyed by the param id in decimal
 * @see com.botwithus.bot.api.GameAPI#getStructType
 */
public record StructType(
        int id,
        Map<String, Object> params
) {

    /** The integer value of param {@code paramId}, or empty when the struct has none. */
    public OptionalInt intParam(int paramId) {
        return ConfigValues.asInt(params.get(Integer.toString(paramId)));
    }

    /** The string value of param {@code paramId}, or empty when the struct has none. */
    public Optional<String> stringParam(int paramId) {
        return ConfigValues.asString(params.get(Integer.toString(paramId)));
    }
}
