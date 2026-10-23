package com.botwithus.bot.core.runlog;

/** A script on a connection: what "the latest run" and "the last crash" are keyed by. */
record RunKey(String connectionName, String scriptName) {

    RunKey {
        connectionName = connectionName == null ? "" : connectionName;
        scriptName = scriptName == null ? "" : scriptName;
    }
}
