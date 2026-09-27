package com.botwithus.bot.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameStateTest {

    @ParameterizedTest
    @CsvSource({
            "10, LOGIN_SCREEN",
            "20, LOBBY",
            "30, IN_GAME",
            "0, UNKNOWN",
            "25, UNKNOWN",
            "40, UNKNOWN",
            "-1, UNKNOWN",
    })
    void fromWire_mapsTheSettledCodesAndNothingElse(int code, GameState expected) {
        assertEquals(expected, GameState.fromWire(code));
    }
}
