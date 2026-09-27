package com.botwithus.bot.cli.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * One-shot move of the auto-start globals out of the legacy
 * {@code autostart.properties} into {@link HostSettings}.
 *
 * <p>Copies {@code autoConnect}, {@code pipePrefix} (as {@code autoConnectPipes})
 * and {@code scanIntervalMs}, writes the settings file, and only then renames the
 * legacy file to {@code autostart.properties.bak}. If the settings write fails the
 * legacy file stays put and the next start tries again. Once the legacy file is
 * gone the migration does nothing, so running it again is harmless.</p>
 *
 * <p>Per-account and per-group auto-start profiles ({@code profiles/}) are not
 * touched; they stay with {@code ScriptProfileStore}. The legacy {@code probeLobby}
 * key was never read by anything and is left in the backup.</p>
 */
public final class SettingsMigration {

    /** The legacy file, relative to the data folder. */
    public static final String LEGACY_FILE_NAME = "autostart.properties";

    /** Appended to the legacy file's name once it has been migrated. */
    public static final String BACKUP_SUFFIX = ".bak";

    private static final Logger log = LoggerFactory.getLogger(SettingsMigration.class);
    private static final String LEGACY_AUTO_CONNECT = "autoConnect";
    private static final String LEGACY_PIPE_PREFIX = "pipePrefix";
    private static final String LEGACY_SCAN_INTERVAL = "scanIntervalMs";

    /** How a run ended. */
    public enum Outcome {
        /** No legacy file; nothing to do. */
        NOTHING_TO_MIGRATE,
        /** Values copied, settings written, legacy file renamed. */
        MIGRATED,
        /** Could not read the legacy file or write the settings; the legacy file was left in place. */
        FAILED
    }

    /**
     * @param outcome    how the run ended
     * @param copiedKeys the settings that were copied, by their new names
     */
    public record Result(Outcome outcome, List<String> copiedKeys) {

        public Result {
            copiedKeys = List.copyOf(copiedKeys);
        }
    }

    private SettingsMigration() {}

    /** Migrates {@code <baseDir>/autostart.properties} into {@code settings}, if it exists. */
    public static Result run(Path baseDir, HostSettings settings) {
        Path legacy = baseDir.resolve(LEGACY_FILE_NAME);
        if (!Files.isRegularFile(legacy)) {
            return new Result(Outcome.NOTHING_TO_MIGRATE, List.of());
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(legacy)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read {} to migrate it; leaving it in place: {}", legacy, e.getMessage());
            return new Result(Outcome.FAILED, List.of());
        }
        List<String> copied = copyGlobals(props, settings);
        return switch (settings.flush()) {
            case SaveStatus.Failed failed -> {
                log.warn("Could not save migrated settings; {} left in place: {}", legacy, failed.message());
                yield new Result(Outcome.FAILED, copied);
            }
            case SaveStatus.Saved _, SaveStatus.Saving _ -> retire(legacy, copied);
        };
    }

    private static List<String> copyGlobals(Properties props, HostSettings settings) {
        List<String> copied = new ArrayList<>();
        String autoConnect = props.getProperty(LEGACY_AUTO_CONNECT);
        if (autoConnect != null) {
            // The legacy reader was Boolean.parseBoolean: anything but "true" meant off.
            settings.set(SettingKeys.AUTO_CONNECT, Boolean.parseBoolean(autoConnect.strip()));
            copied.add(SettingKeys.AUTO_CONNECT.name());
        }
        copyText(props, LEGACY_PIPE_PREFIX, SettingKeys.PIPE_PREFIX, settings, copied);
        copyText(props, LEGACY_SCAN_INTERVAL, SettingKeys.SCAN_INTERVAL_MS, settings, copied);
        return copied;
    }

    private static void copyText(Properties props, String legacyName, SettingKey<?> key,
                                 HostSettings settings, List<String> copied) {
        String text = props.getProperty(legacyName);
        if (text == null) {
            return;
        }
        try {
            settings.setText(key.name(), text);
            copied.add(key.name());
        } catch (InvalidSettingException e) {
            log.warn("Not migrating {}={} from {}: {}; the default {} applies",
                    legacyName, text, LEGACY_FILE_NAME, e.getMessage(), key.formattedDefault());
        }
    }

    private static Result retire(Path legacy, List<String> copied) {
        Path backup = legacy.resolveSibling(LEGACY_FILE_NAME + BACKUP_SUFFIX);
        try {
            Files.move(legacy, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Migrated {} but could not rename it to {}: {}", legacy, backup.getFileName(),
                    e.getMessage());
            return new Result(Outcome.FAILED, copied);
        }
        log.info("Moved auto-connect settings {} from {} into {}; the old file is now {}",
                copied, LEGACY_FILE_NAME, HostSettings.FILE_NAME, backup.getFileName());
        return new Result(Outcome.MIGRATED, copied);
    }
}
