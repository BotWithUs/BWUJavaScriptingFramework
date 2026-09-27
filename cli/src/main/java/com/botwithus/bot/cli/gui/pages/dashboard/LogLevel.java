package com.botwithus.bot.cli.gui.pages.dashboard;

/** The Logs tab's level chips. {@link #ALL} shows every level. */
public enum LogLevel {
    ALL, ERROR, WARN, INFO, DEBUG;

    /** Whether a line logged at {@code level} shows under this chip. */
    public boolean admits(String level) {
        return this == ALL || name().equals(level);
    }
}
