package com.botwithus.bot.core.runlog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** The two timestamp shapes a run log uses: ISO-8601 UTC with milliseconds, and the file-name stamp. */
final class RunLogClock {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private RunLogClock() {
    }

    /** {@code 2026-10-06T07:12:23.529Z}. */
    static String format(Instant at) {
        return ISO_MILLIS.format(at);
    }

    /** {@code 20261006-071223}, for the run log's file name. */
    static String fileStamp(Instant at) {
        return FILE_STAMP.format(at);
    }
}
