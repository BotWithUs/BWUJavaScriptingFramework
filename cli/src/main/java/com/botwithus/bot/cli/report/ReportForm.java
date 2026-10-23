package com.botwithus.bot.cli.report;

import com.botwithus.bot.core.report.ProblemKind;
import com.botwithus.bot.core.report.ReportRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * What the user has filled in: what went wrong, and what they were doing. A
 * report needs both, and the note needs {@value ReportRequest#MIN_NOTE_CHARS}
 * characters; Send stays off until {@link #isComplete()}.
 *
 * @param problem the chosen answer, if any
 * @param note    the note as typed
 */
public record ReportForm(Optional<ProblemKind> problem, String note) {

    public ReportForm {
        Objects.requireNonNull(problem, "problem");
        note = note == null ? "" : note;
    }

    /** Whether the report can be sent. */
    public boolean isComplete() {
        return problem.isPresent() && ReportRequest.isNoteLongEnough(note);
    }

    /** How many more characters the note needs; {@code 0} once it is long enough. */
    public int charactersShort() {
        return Math.max(0, ReportRequest.MIN_NOTE_CHARS - ReportRequest.noteLength(note));
    }

    /**
     * One friendly line saying what is still missing, shown under the note while
     * Send is off; empty once the form is complete.
     */
    public Optional<String> hint() {
        int more = charactersShort();
        String words = more == 1 ? "1 more character" : more + " more characters";
        if (problem.isEmpty() && more > 0) {
            return Optional.of("Choose what went wrong, and tell us a little more (" + words + ").");
        }
        if (problem.isEmpty()) {
            return Optional.of("Choose what went wrong above.");
        }
        if (more > 0) {
            return Optional.of("Tell us a little more: " + words + " to go.");
        }
        return Optional.empty();
    }
}
