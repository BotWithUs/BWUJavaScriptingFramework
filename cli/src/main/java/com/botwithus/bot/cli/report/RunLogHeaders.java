package com.botwithus.bot.cli.report;

import com.botwithus.bot.core.runlog.RunLogHeader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads a value back out of a run log's header, for a run that is no longer open. */
final class RunLogHeaders {

    private static final String RUN_ID_KEY = "run_id: ";
    /** The header has sixteen keys; stop well past them if the end line is missing. */
    private static final int MAX_HEADER_LINES = 40;

    private RunLogHeaders() {
    }

    /** The {@code run_id} the log's header names; empty when unreadable or absent. */
    static Optional<String> runId(Path log) {
        try (BufferedReader in = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String line;
            for (int i = 0; i < MAX_HEADER_LINES && (line = in.readLine()) != null; i++) {
                if (line.equals(RunLogHeader.END)) {
                    break;
                }
                if (line.startsWith(RUN_ID_KEY)) {
                    String id = line.substring(RUN_ID_KEY.length()).strip();
                    return id.isEmpty() || id.equals(RunLogHeader.UNKNOWN) ? Optional.empty() : Optional.of(id);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
