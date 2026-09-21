package com.botwithus.bot.api.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the facing convention. The four cardinal rows are the contract: raw grows clockwise
 * seen from above, {@code 0} is south and {@code 8192} is north. If a live check moves the
 * zero offset, {@link Orientation#NORTH_RAW} changes and these rows say what must follow.
 */
class OrientationTest {

    private static final double EPSILON = 1e-9;

    @ParameterizedTest
    @CsvSource({
        "8192,  0.0,   NORTH",
        "12288, 90.0,  EAST",
        "0,     180.0, SOUTH",
        "4096,  270.0, WEST",
    })
    void cardinals_mapToBearingAndCompassPoint(int raw, double bearing, Direction point) {
        Orientation facing = new Orientation(raw);

        assertEquals(bearing, facing.degrees().getAsDouble(), EPSILON);
        assertEquals(Optional.of(point), facing.compass());
    }

    @ParameterizedTest
    @CsvSource({
        "10240, NORTH_EAST",
        "14336, SOUTH_EAST",
        "2048,  SOUTH_WEST",
        "6144,  NORTH_WEST",
    })
    void intercardinals_mapToTheirCompassPoint(int raw, Direction point) {
        assertEquals(Optional.of(point), new Orientation(raw).compass());
    }

    @Test
    void justShortOfNorth_wrapsToNorthRatherThanOverflowing() {
        Orientation facing = new Orientation(Orientation.NORTH_RAW - 1);

        assertTrue(facing.degrees().getAsDouble() > Direction.NORTH_WEST.degrees());
        assertEquals(Optional.of(Direction.NORTH), facing.compass());
    }

    @Test
    void wireSentinel_decodesToUnknown_withNoBearingAndNoPoint() {
        Orientation facing = Orientation.fromWire(Orientation.WIRE_UNKNOWN);

        assertFalse(facing.isKnown());
        assertEquals(Orientation.UNKNOWN_RAW, facing.raw());
        assertEquals(OptionalDouble.empty(), facing.degrees());
        assertEquals(Optional.empty(), facing.compass());
    }

    @Test
    void wireAngle_decodesToItself() {
        Orientation facing = Orientation.fromWire(Orientation.NORTH_RAW);

        assertTrue(facing.isKnown());
        assertEquals(Orientation.NORTH_RAW, facing.raw());
    }

    @ParameterizedTest
    @ValueSource(ints = {Orientation.FULL_TURN, 0xFFFE, -2})
    void outOfContractValue_isRefusedRatherThanWrapped(int raw) {
        assertThrows(IllegalArgumentException.class, () -> Orientation.fromWire(raw));
    }

    @Test
    void directionDegrees_areClockwiseFromNorthInFortyFiveDegreeSteps() {
        assertEquals(0.0, Direction.NORTH.degrees(), EPSILON);
        assertEquals(90.0, Direction.EAST.degrees(), EPSILON);
        assertEquals(315.0, Direction.NORTH_WEST.degrees(), EPSILON);
    }
}
