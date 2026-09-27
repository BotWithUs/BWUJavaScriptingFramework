package com.botwithus.bot.cli.gui.pages.store;

/** What the Store's installs are doing, for the line it shows about them. */
public sealed interface InstallActivity {

    /** Nothing to report. */
    record Idle() implements InstallActivity {}

    /** {@code count} scripts are on their way. */
    record Installing(int count) implements InstallActivity {}

    /** The last install landed; {@code message} says where. */
    record Finished(String message) implements InstallActivity {}

    /** The last install did not land; {@code message} says why and what to do. */
    record Failed(String message) implements InstallActivity {}
}
