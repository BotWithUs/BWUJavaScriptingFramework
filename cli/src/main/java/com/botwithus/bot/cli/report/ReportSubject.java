package com.botwithus.bot.cli.report;

import com.botwithus.bot.core.report.ProblemKind;

import java.util.Objects;
import java.util.Optional;

/**
 * The script a report is about, on the client it ran on.
 *
 * @param connectionName the connection's name (its pipe), which run logs are keyed by
 * @param scriptName     the script's registered name
 * @param likelyProblem  the answer to preselect: "It stopped with an error" when
 *                       the report starts from a crash toast, else none
 */
public record ReportSubject(String connectionName, String scriptName, Optional<ProblemKind> likelyProblem) {

    public ReportSubject {
        Objects.requireNonNull(connectionName, "connectionName");
        Objects.requireNonNull(scriptName, "scriptName");
        Objects.requireNonNull(likelyProblem, "likelyProblem");
    }

    /** A report with nothing preselected. */
    public ReportSubject(String connectionName, String scriptName) {
        this(connectionName, scriptName, Optional.empty());
    }

    /** A report about a crash the user has just been told about. */
    public static ReportSubject afterCrash(String connectionName, String scriptName) {
        return new ReportSubject(connectionName, scriptName, Optional.of(ProblemKind.CRASHED));
    }
}
