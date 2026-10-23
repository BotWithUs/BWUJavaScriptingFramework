package com.botwithus.bot.cli.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where {@link HostSettings} keeps its text entries. {@link PropertiesFileStorage}
 * is the only production implementation; the seam exists so a test can count or
 * fail writes around the real file.
 */
interface SettingsStorage {

    /** Reads every entry; an absent file is an empty map. */
    Map<String, String> load() throws IOException;

    /** Replaces the stored entries with {@code entries}, atomically. */
    void save(Map<String, String> entries) throws IOException;

    /** The file shown to the user as where settings live. */
    Path location();
}
