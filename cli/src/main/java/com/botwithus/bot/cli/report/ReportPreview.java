package com.botwithus.bot.cli.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a report will carry, as the dialog lists it before the user sends it.
 *
 * @param scriptName the script it is about
 * @param crashLine  the error the script stopped with, if it crashed
 * @param logFiles   the script's own run logs the launcher will include, newest first
 * @param hasLogs    whether the host has a run log for the script at all; without one
 *                   there is nothing to send, and the dialog says so instead of asking
 */
public record ReportPreview(String scriptName, Optional<String> crashLine, List<String> logFiles,
                            boolean hasLogs) {

    /** How many of a script's run logs a report carries. */
    public static final int LOGS_SENT = 3;
    private static final String LOG_SUFFIX = ".log";

    public ReportPreview {
        Objects.requireNonNull(scriptName, "scriptName");
        Objects.requireNonNull(crashLine, "crashLine");
        logFiles = List.copyOf(logFiles);
    }

    /**
     * The names of the newest run logs in {@code dir}. Their names start with
     * the run's start time, so the name order is the age order. Empty when the
     * directory cannot be read.
     */
    static List<String> newestLogs(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(LOG_SUFFIX))
                    .sorted(Comparator.reverseOrder())
                    .limit(LOGS_SENT)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
