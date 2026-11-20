package com.botwithus.bot.api.model;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads one value out of the untyped maps that cache configs and client-script results
 * arrive in ({@link StructType#params()}, {@link EnumType#entries()},
 * {@link ScriptResult#returns()}).
 *
 * <p>Those maps are decoded from JSON or msgpack, so a value is a boxed number or a string
 * and nothing in the type system says which. This class is the one place that asks. The
 * cache's JSON goes through Gson into {@code Map<String, Object>}, which makes every number a
 * {@code Double}; msgpack gives {@code Integer} or {@code Long}. All three are accepted when
 * they hold a whole number in {@code int} range.</p>
 */
final class ConfigValues {

    private ConfigValues() {}

    /** The value as an {@code int}, or empty when it is absent or not a whole number. */
    static OptionalInt asInt(Object value) {
        // rule-exception: {rule:no-instanceof} — decode boundary. The value comes from an
        // untyped JSON/msgpack map, so a type test is the only way to tell a number from a
        // string. Kept to this one switch so no caller repeats it.
        return switch (value) {
            case Integer i -> OptionalInt.of(i);
            case Long l when l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ->
                    OptionalInt.of(l.intValue());
            case Double d when d == Math.rint(d) && d >= Integer.MIN_VALUE
                    && d <= Integer.MAX_VALUE -> OptionalInt.of(d.intValue());
            case Short s -> OptionalInt.of(s);
            case Byte b -> OptionalInt.of(b);
            case null, default -> OptionalInt.empty();
        };
    }

    /**
     * Param {@code paramId} of a cache definition's param map as an {@code int}. The map is
     * keyed by the param id in decimal and may be {@code null} on a definition with no params.
     */
    static OptionalInt intParam(Map<String, Object> params, int paramId) {
        return params == null ? OptionalInt.empty() : asInt(params.get(Integer.toString(paramId)));
    }

    /** Param {@code paramId} as a string; see {@link #intParam(Map, int)}. */
    static Optional<String> stringParam(Map<String, Object> params, int paramId) {
        return params == null ? Optional.empty() : asString(params.get(Integer.toString(paramId)));
    }

    /** The value as a string, or empty when it is absent or not a string. */
    static Optional<String> asString(Object value) {
        // rule-exception: {rule:no-instanceof} — same decode boundary as asInt.
        return switch (value) {
            case String s -> Optional.of(s);
            case null, default -> Optional.empty();
        };
    }
}
