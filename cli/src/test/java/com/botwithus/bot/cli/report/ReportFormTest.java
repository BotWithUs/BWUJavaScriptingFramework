package com.botwithus.bot.cli.report;

import com.botwithus.bot.core.report.ProblemKind;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What gates Send, and the one line under the note that says what is still missing. */
class ReportFormTest {

    private static final String FIFTEEN = "it froze there.";

    @Test
    void complete_onlyWithAnAnswerAndFifteenCharacters() {
        assertAll(
                () -> assertTrue(new ReportForm(Optional.of(ProblemKind.OTHER), FIFTEEN).isComplete()),
                () -> assertFalse(new ReportForm(Optional.empty(), FIFTEEN).isComplete()),
                () -> assertFalse(new ReportForm(Optional.of(ProblemKind.OTHER), "it froze there").isComplete()),
                () -> assertFalse(new ReportForm(Optional.of(ProblemKind.OTHER), null).isComplete()));
    }

    @Test
    void hint_namesWhatIsMissing_andGoesWhenComplete() {
        assertAll(
                () -> assertEquals(Optional.of("Choose what went wrong, and tell us a little more "
                        + "(15 more characters)."), new ReportForm(Optional.empty(), "").hint()),
                () -> assertEquals(Optional.of("Choose what went wrong above."),
                        new ReportForm(Optional.empty(), FIFTEEN).hint()),
                () -> assertEquals(Optional.of("Tell us a little more: 1 more character to go."),
                        new ReportForm(Optional.of(ProblemKind.CRASHED), "it froze there").hint()),
                () -> assertEquals(Optional.empty(),
                        new ReportForm(Optional.of(ProblemKind.CRASHED), FIFTEEN).hint()));
    }
}
