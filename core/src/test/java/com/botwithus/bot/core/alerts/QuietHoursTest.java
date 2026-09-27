package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietHoursTest {

    private static final QuietHours DAYTIME = new QuietHours(LocalTime.of(9, 0), LocalTime.of(17, 0));
    private static final QuietHours OVERNIGHT = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));

    @ParameterizedTest
    @CsvSource({"08:59,false", "09:00,true", "12:00,true", "16:59,true", "17:00,false", "23:00,false"})
    void contains_sameDayWindow(String time, boolean expected) {
        assertEquals(expected, DAYTIME.contains(LocalTime.parse(time)));
    }

    @ParameterizedTest
    @CsvSource({"21:59,false", "22:00,true", "23:59,true", "00:00,true", "03:30,true",
            "06:59,true", "07:00,false", "12:00,false"})
    void contains_windowWrappingMidnight(String time, boolean expected) {
        assertEquals(expected, OVERNIGHT.contains(LocalTime.parse(time)));
    }

    @Test
    void contains_equalEnds_isNeverQuiet() {
        QuietHours none = new QuietHours(LocalTime.of(8, 0), LocalTime.of(8, 0));

        assertFalse(none.contains(LocalTime.of(8, 0)));
        assertFalse(none.contains(LocalTime.of(20, 0)));
    }

    @Test
    void lets_crashThroughInsideTheWindow() {
        assertTrue(OVERNIGHT.lets(AlertKind.SCRIPT_CRASH, LocalTime.of(2, 0)));
    }

    @ParameterizedTest
    @EnumSource(value = AlertKind.class, names = "SCRIPT_CRASH", mode = EnumSource.Mode.EXCLUDE)
    void lets_holdsEveryOtherKindInsideTheWindow(AlertKind kind) {
        assertFalse(OVERNIGHT.lets(kind, LocalTime.of(2, 0)));
    }

    @ParameterizedTest
    @EnumSource(AlertKind.class)
    void lets_everythingOutsideTheWindow(AlertKind kind) {
        assertTrue(OVERNIGHT.lets(kind, LocalTime.of(12, 0)));
    }
}
