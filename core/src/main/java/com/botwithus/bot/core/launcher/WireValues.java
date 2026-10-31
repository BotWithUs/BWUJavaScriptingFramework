package com.botwithus.bot.core.launcher;

import com.botwithus.msgpack.MsgArray;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgValue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Typed reads of a decoded msgpack map, for the launcher protocol. A missing
 * key and a value of the wrong type read the same: absent. Unknown keys are
 * ignored, which is what keeps additive protocol changes safe (ADR 2.4). A key
 * whose bytes are not valid UTF-8 never matches a lookup.
 */
final class WireValues {

    private WireValues() {
    }

    /** @return the value under {@code key}, if {@code map} is a map that has it */
    static Optional<MsgValue> field(MsgValue map, String key) {
        return map.mapValue().flatMap(m -> m.get(key));
    }

    /** @return the string under {@code key} */
    static Optional<String> string(MsgValue map, String key) {
        return field(map, key).flatMap(MsgValue::stringValue);
    }

    /** @return the string under {@code key}, or {@code fallback} */
    static String string(MsgValue map, String key, String fallback) {
        return string(map, key).orElse(fallback);
    }

    /** @return the integer under {@code key}, if it fits a {@code long} */
    static OptionalLong integer(MsgValue map, String key) {
        return field(map, key).map(MsgValue::longValue).orElseGet(OptionalLong::empty);
    }

    /** @return the integer under {@code key}, or {@code fallback} */
    static long integer(MsgValue map, String key, long fallback) {
        return integer(map, key).orElse(fallback);
    }

    /** @return a copy of the binary under {@code key} */
    static Optional<byte[]> binary(MsgValue map, String key) {
        return field(map, key).flatMap(MsgValue::binaryValue);
    }

    /** @return the boolean under {@code key}, or {@code fallback} */
    static boolean bool(MsgValue map, String key, boolean fallback) {
        return field(map, key).flatMap(MsgValue::booleanValue).orElse(fallback);
    }

    /** @return the array under {@code key}; empty when absent */
    static List<MsgValue> array(MsgValue map, String key) {
        return field(map, key).flatMap(MsgValue::arrayValue).map(MsgArray::values).orElse(List.of());
    }

    /** @return the map under {@code key}; an empty map when absent */
    static MsgValue map(MsgValue map, String key) {
        return field(map, key).filter(WireValues::isMap).orElse(MsgMap.empty());
    }

    /** @return whether {@code value} is a map */
    static boolean isMap(MsgValue value) {
        return value.mapValue().isPresent();
    }
}
