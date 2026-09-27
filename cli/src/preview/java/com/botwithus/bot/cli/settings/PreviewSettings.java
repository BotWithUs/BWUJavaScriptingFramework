package com.botwithus.bot.cli.settings;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DEV ONLY. Real {@link HostSettings} over the real key catalogue, kept in
 * memory so the preview never writes a settings file. Lives in the preview
 * source set; nothing here ships.
 */
public final class PreviewSettings {

    private static final Path SHOWN_FILE = Path.of(System.getProperty("user.home"), ".botwithus",
            HostSettings.FILE_NAME);

    private PreviewSettings() {
    }

    /** Settings that start from {@code entries}, as if read from {@code config.properties}. */
    public static HostSettings inMemory(Map<String, String> entries) {
        return new HostSettings(new MemoryStorage(entries), SettingKeys.ALL, () -> { }, Clock.systemUTC());
    }

    private static final class MemoryStorage implements SettingsStorage {

        private Map<String, String> entries;

        MemoryStorage(Map<String, String> entries) {
            this.entries = new LinkedHashMap<>(entries);
        }

        @Override
        public synchronized Map<String, String> load() {
            return new LinkedHashMap<>(entries);
        }

        @Override
        public synchronized void save(Map<String, String> saved) {
            entries = new LinkedHashMap<>(saved);
        }

        @Override
        public Path location() {
            return SHOWN_FILE;
        }
    }
}
