package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.snapshot.Orientation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrientationWireDecoderTest {

    private static final int FIRST_BAD = 0xFFFE;
    private static final int SECOND_BAD = Orientation.FULL_TURN;
    private static final int ROWS_PER_TICK = 50;

    @Test
    void decode_repeatedOutOfContractValues_reportsOnlyTheFirstAndNeverThrows() {
        List<Integer> reported = new ArrayList<>();
        OrientationWireDecoder decoder = new OrientationWireDecoder(reported::add);

        for (int row = 0; row < ROWS_PER_TICK; row++) {
            Orientation facing = assertDoesNotThrow(() -> decoder.decode(FIRST_BAD));
            assertFalse(facing.isKnown());
        }
        assertFalse(decoder.decode(SECOND_BAD).isKnown());

        assertEquals(List.of(FIRST_BAD), reported);
    }

    @Test
    void decode_inContractValues_areNotReported() {
        List<Integer> reported = new ArrayList<>();
        OrientationWireDecoder decoder = new OrientationWireDecoder(reported::add);

        assertEquals(Orientation.NORTH_RAW, decoder.decode(Orientation.NORTH_RAW).raw());
        assertFalse(decoder.decode(Orientation.WIRE_UNKNOWN).isKnown());

        assertEquals(List.of(), reported);
    }

    @Test
    void decode_separateDecoders_eachReportTheirOwnFirstOccurrence() {
        List<Integer> reported = new ArrayList<>();

        new OrientationWireDecoder(reported::add).decode(FIRST_BAD);
        new OrientationWireDecoder(reported::add).decode(FIRST_BAD);

        assertEquals(List.of(FIRST_BAD, FIRST_BAD), reported);
    }
}
