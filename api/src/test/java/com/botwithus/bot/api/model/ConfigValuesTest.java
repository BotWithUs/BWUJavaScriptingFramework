package com.botwithus.bot.api.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The typed accessors over cache and script maps. The decoder hands numbers back as Integer
 * or Long depending on the source, so both are exercised; a string where a number is asked
 * for, and a long that does not fit an int, must read as absent rather than as garbage.
 */
class ConfigValuesTest {

    @Test
    void structParams_readIntsAndStrings_byParamId() {
        StructType struct = new StructType(14682, Map.of("2793", 3, "2794", "Backhand",
                "2796", 25L, "9999", (long) Integer.MAX_VALUE + 1));

        assertEquals(OptionalInt.of(3), struct.intParam(2793));
        assertEquals(OptionalInt.of(25), struct.intParam(2796));
        assertEquals(Optional.of("Backhand"), struct.stringParam(2794));
        assertEquals(OptionalInt.empty(), struct.intParam(2794));
        assertEquals(OptionalInt.empty(), struct.intParam(9999));
        assertEquals(OptionalInt.empty(), struct.intParam(1));
    }

    @Test
    void gsonShapedParams_doublesHoldingWholeNumbers_readAsInts() {
        // Gson decodes Map<String, Object> numbers as Double: this is what NXTCache returns.
        StructType struct = new StructType(14541, Map.of("2793", 1.0, "2807", 28.0, "2945", 1.5));

        assertEquals(OptionalInt.of(1), struct.intParam(2793));
        assertEquals(OptionalInt.of(28), struct.intParam(2807));
        assertEquals(OptionalInt.empty(), struct.intParam(2945));
    }

    @Test
    void enumIntValue_doesNotSubstituteTheDefault() {
        EnumType type = new EnumType(13199, 0, 26, 77, "", 2, Map.of("1", 10147, "17", 16973L));

        assertEquals(OptionalInt.of(16973), type.intValue(17));
        assertEquals(OptionalInt.empty(), type.intValue(2));
        assertEquals(Map.of(1, 10147, 17, 16973), type.intEntries());
    }

    @Test
    void scriptResult_intAt_boundsAndTypes() {
        ScriptResult result = new ScriptResult(Arrays.asList(5, "x", null));

        assertEquals(OptionalInt.of(5), result.intAt(0));
        assertEquals(OptionalInt.empty(), result.intAt(1));
        assertEquals(OptionalInt.empty(), result.intAt(2));
        assertEquals(OptionalInt.empty(), result.intAt(3));
        assertEquals(OptionalInt.empty(), new ScriptResult(List.of()).intAt(0));
    }
}
