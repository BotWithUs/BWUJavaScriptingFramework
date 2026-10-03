package com.botwithus.bot.core.launcher;

import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Typed reads of a decoded msgpack map, for the launcher protocol. A missing
 * key and a value of the wrong type read the same: absent. Unknown keys are
 * ignored, which is what keeps additive protocol changes safe (ADR 2.4).
 */
final class WireValues {

    private WireValues() {
    }

    /** @return the value under {@code key}, if {@code map} is a map that has it */
    static Optional<Value> field(Value map, String key) {
        if (!map.isMapValue()) {
            return Optional.empty();
        }
        Value[] keyValues = map.asMapValue().getKeyValueArray();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Value k = keyValues[i];
            if (k.isStringValue() && k.asStringValue().asString().equals(key)) {
                return Optional.of(keyValues[i + 1]);
            }
        }
        return Optional.empty();
    }

    /** @return the string under {@code key} */
    static Optional<String> string(Value map, String key) {
        return field(map, key).filter(Value::isStringValue).map(v -> v.asStringValue().asString());
    }

    /** @return the string under {@code key}, or {@code fallback} */
    static String string(Value map, String key, String fallback) {
        return string(map, key).orElse(fallback);
    }

    /** @return the integer under {@code key}, if it fits a {@code long} */
    static OptionalLong integer(Value map, String key) {
        Optional<Value> value = field(map, key)
                .filter(v -> v.isIntegerValue() && v.asIntegerValue().isInLongRange());
        return value.isPresent() ? OptionalLong.of(value.get().asIntegerValue().asLong()) : OptionalLong.empty();
    }

    /** @return the integer under {@code key}, or {@code fallback} */
    static long integer(Value map, String key, long fallback) {
        return integer(map, key).orElse(fallback);
    }

    /** @return the boolean under {@code key}, or {@code fallback} */
    static boolean bool(Value map, String key, boolean fallback) {
        return field(map, key).filter(Value::isBooleanValue)
                .map(v -> v.asBooleanValue().getBoolean()).orElse(fallback);
    }

    /** @return the array under {@code key}; empty when absent */
    static List<Value> array(Value map, String key) {
        return field(map, key).filter(Value::isArrayValue).map(v -> v.asArrayValue().list()).orElse(List.of());
    }

    /** @return the map under {@code key}; an empty map when absent */
    static Value map(Value map, String key) {
        return field(map, key).filter(Value::isMapValue).orElse(ValueFactory.emptyMap());
    }
}
