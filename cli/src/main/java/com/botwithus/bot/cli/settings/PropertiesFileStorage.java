package com.botwithus.bot.cli.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A {@code .properties} file written atomically: the new content goes to a
 * sibling {@code .tmp} file that is then moved over the target, so a crash
 * mid-write leaves either the old file or the new one, never a truncated one.
 *
 * <p>Writes use {@link Properties#store(OutputStream, String)}, which escapes
 * anything outside ISO-8859-1, so the file stays plain ASCII. Reads decode as
 * UTF-8, which reads that ASCII back and also accepts a file hand-edited in UTF-8.</p>
 *
 * <p>A file that cannot be parsed is moved aside to {@code <name>.unreadable}
 * rather than left to be overwritten by the next save.</p>
 */
final class PropertiesFileStorage implements SettingsStorage {

    private static final String HEADER =
            "BotWithUs host settings. Changes made in the app or with 'config set' save automatically.";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String UNREADABLE_SUFFIX = ".unreadable";

    private final Path file;

    PropertiesFileStorage(Path file) {
        this.file = file;
    }

    @Override
    public Map<String, String> load() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return entries;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IllegalArgumentException e) {
            throw quarantine(e);
        }
        for (String name : props.stringPropertyNames()) {
            entries.put(name, props.getProperty(name));
        }
        return entries;
    }

    @Override
    public void save(Map<String, String> entries) throws IOException {
        Properties props = new Properties();
        props.putAll(entries);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, HEADER);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public Path location() {
        return file;
    }

    private IOException quarantine(IllegalArgumentException cause) {
        Path aside = file.resolveSibling(file.getFileName() + UNREADABLE_SUFFIX);
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailure) {
            cause.addSuppressed(moveFailure);
            return new IOException(file + " is not a valid properties file (" + cause.getMessage()
                    + ") and could not be moved aside", cause);
        }
        return new IOException(file + " is not a valid properties file (" + cause.getMessage()
                + "); moved it to " + aside.getFileName() + " and started from defaults", cause);
    }
}
