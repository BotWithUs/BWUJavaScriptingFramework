package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.config.AtomicFiles;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the host's settings into one zip to carry to another PC: every
 * {@code .properties} and {@code .json} file under the data folder
 * ({@code config.properties}, account profiles, groups, favourites, per-script
 * configs), with paths kept relative to it.
 *
 * <p><b>Secrets never go in.</b> The host keeps none in these files today — alert
 * tokens and webhook URLs are meant for the Windows credential store — but a
 * file whose name mentions a credential, secret, token or password is left out
 * all the same, so a later file that breaks that rule does not leak through an
 * export. Folders that hold no settings (native libraries, staged script copies,
 * the scripts themselves, logs and the launcher exchange) are skipped whole.</p>
 */
public final class SettingsExport {

    /** Passes writes through and leaves the stream under it open when closed. */
    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static final int MAX_DEPTH = 4;
    private static final Set<String> SETTINGS_EXTENSIONS = Set.of(".properties", ".json");
    private static final List<String> SECRET_WORDS = List.of("credential", "secret", "token", "password");
    private static final Set<String> SKIPPED_FOLDERS = Set.of("native", "staged-scripts", "scripts", "logs", "sdn");
    private static final char ZIP_SEPARATOR = '/';

    private SettingsExport() {
    }

    /**
     * Writes the zip of {@code dataFolder}'s settings to {@code zip}, atomically.
     *
     * @return the paths packed, relative to {@code dataFolder}
     */
    public static List<Path> write(Path dataFolder, Path zip) throws IOException {
        List<Path> files = settingsFiles(dataFolder);
        AtomicFiles.write(zip, out -> {
            // The zip is closed to free its deflater; the file under it stays open for AtomicFiles to sync.
            try (ZipOutputStream zipOut = new ZipOutputStream(new KeepOpen(out))) {
                for (Path relative : files) {
                    zipOut.putNextEntry(new ZipEntry(entryName(relative)));
                    Files.copy(dataFolder.resolve(relative), zipOut);
                    zipOut.closeEntry();
                }
                zipOut.finish();
            }
        });
        return files;
    }

    /** The settings files under {@code dataFolder} an export packs, relative to it, sorted. */
    public static List<Path> settingsFiles(Path dataFolder) throws IOException {
        if (!Files.isDirectory(dataFolder)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dataFolder, MAX_DEPTH)) {
            return walk.filter(Files::isRegularFile)
                    .map(dataFolder::relativize)
                    .filter(SettingsExport::isPacked)
                    .sorted()
                    .toList();
        }
    }

    /** Whether a file at {@code relative} under the data folder goes into an export. */
    static boolean isPacked(Path relative) {
        if (relative.getNameCount() > 1 && SKIPPED_FOLDERS.contains(lower(relative.getName(0)))) {
            return false;
        }
        String name = lower(relative.getFileName());
        boolean isSettings = SETTINGS_EXTENSIONS.stream().anyMatch(name::endsWith);
        boolean isSecret = SECRET_WORDS.stream().anyMatch(name::contains);
        return isSettings && !isSecret;
    }

    private static String entryName(Path relative) {
        StringBuilder name = new StringBuilder();
        for (Path part : relative) {
            if (!name.isEmpty()) {
                name.append(ZIP_SEPARATOR);
            }
            name.append(part);
        }
        return name.toString();
    }

    private static String lower(Path part) {
        return part.toString().toLowerCase(Locale.ROOT);
    }
}
