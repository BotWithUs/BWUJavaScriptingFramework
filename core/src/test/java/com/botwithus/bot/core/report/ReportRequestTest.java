package com.botwithus.bot.core.report;

import com.botwithus.bot.core.runlog.KnownNames;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two fields revision 5 makes mandatory: the problem and a note of 15 characters or more. */
class ReportRequestTest {

    private static final String EMOJI = "😀";

    private static ReportRequest withNote(String note) {
        return new ReportRequest("Agility", "agility", OptionalLong.empty(), Optional.empty(), "2.0",
                Optional.empty(), Optional.empty(), OptionalLong.empty(), KnownNames.NONE, Optional.empty(),
                ProblemKind.OTHER, note);
    }

    @Test
    void noteLength_countsTrimmedCodePoints() {
        String fourteen = "a".repeat(ReportRequest.MIN_NOTE_CHARS - 1);
        assertAll(
                () -> assertFalse(ReportRequest.isNoteLongEnough(null)),
                () -> assertFalse(ReportRequest.isNoteLongEnough("   " + fourteen + "   ")),
                () -> assertTrue(ReportRequest.isNoteLongEnough(fourteen + "b")),
                // Fourteen code points, fifteen UTF-16 units: still too short.
                () -> assertFalse(ReportRequest.isNoteLongEnough("a".repeat(13) + EMOJI)),
                () -> assertEquals(ReportRequest.MIN_NOTE_CHARS - 1, ReportRequest.noteLength("a".repeat(13) + EMOJI)));
    }

    @Test
    void constructor_refusesAShortNote_andANullProblem() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> withNote("too short")),
                () -> assertThrows(IllegalArgumentException.class, () -> withNote(null)),
                () -> assertThrows(NullPointerException.class, () -> new ReportRequest("A", "a",
                        OptionalLong.empty(), Optional.empty(), "2.0", Optional.empty(), Optional.empty(),
                        OptionalLong.empty(), KnownNames.NONE, Optional.empty(), null, "long enough note here")));
    }

    @Test
    void problemKinds_spellTheFourWireValues_andReadThemBack() {
        assertAll(
                () -> assertEquals(List.of("crashed", "stuck", "wrong_action", "other"),
                        Arrays.stream(ProblemKind.values()).map(ProblemKind::wireName).toList()),
                () -> assertEquals(List.of("It stopped with an error", "It got stuck or stopped doing anything",
                                "It did the wrong thing", "Something else"),
                        Arrays.stream(ProblemKind.values()).map(ProblemKind::label).toList()),
                () -> assertEquals(Optional.of(ProblemKind.WRONG_ACTION), ProblemKind.fromWire("wrong_action")),
                () -> assertEquals(Optional.empty(), ProblemKind.fromWire("Crashed")));
    }
}
