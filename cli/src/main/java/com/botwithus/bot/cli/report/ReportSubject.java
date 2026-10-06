package com.botwithus.bot.cli.report;

import java.util.Objects;

/**
 * The script a report is about, on the client it ran on.
 *
 * @param connectionName the connection's name (its pipe), which run logs are keyed by
 * @param scriptName     the script's registered name
 */
public record ReportSubject(String connectionName, String scriptName) {

    public ReportSubject {
        Objects.requireNonNull(connectionName, "connectionName");
        Objects.requireNonNull(scriptName, "scriptName");
    }
}
