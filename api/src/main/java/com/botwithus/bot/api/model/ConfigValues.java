package com.botwithus.bot.api.model;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads one value out of the untyped maps that cache configs and client-script results
 * arrive in ({@link StructType#params()}, {@link EnumType#entries()},
 * {@link ScriptResult#returns()}).
 *
 * <p>Those maps are decoded from JSON or msgpack, so a value is a boxed number or a string
 * and nothing in the type system says which. This class is the one place that asks.</p>
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
            case Short s -> OptionalInt.of(s);
            case Byte b -> OptionalInt.of(b);
            case null, default -> OptionalInt.empty();
        };
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
