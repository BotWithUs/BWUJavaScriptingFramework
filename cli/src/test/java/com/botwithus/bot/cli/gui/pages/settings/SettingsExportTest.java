package com.botwithus.bot.cli.gui.pages.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsExportTest {

    @TempDir
    Path data;

    @TempDir
    Path out;

    private void file(String relative, String content) throws IOException {
        Path path = data.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void theZipHoldsTheSettingsFilesAndNoSecretsOrOtherFolders() throws IOException {
        file("config.properties", "autoConnect=false\n");
        file("groups.json", "[]");
        file("profiles/3f9a1c2e.properties", "scripts=Woodcutting\n");
        file("config/Woodcutting/settings.json", "{}");
        file("integrations-credentials.json", "{\"hook\":\"x\"}");
        file("discord_SECRET.properties", "url=x\n");
        file("ntfy-token.json", "{}");
        file("native/gameval.json", "{}");
        file("staged-scripts/run-1/meta.json", "{}");
        file("sdn/request.json", "{}");
        file("notes.txt", "not settings");
        file("autostart.properties.bak", "old");
        Path zip = out.resolve("export.zip");

        List<Path> packed = SettingsExport.write(data, zip);

        List<String> expected = List.of("config.properties", "config/Woodcutting/settings.json", "groups.json",
                "profiles/3f9a1c2e.properties");
        assertEquals(expected, entries(zip));
        assertEquals(expected.size(), packed.size());
        assertEquals("autoConnect=false\n", contentOf(zip, "config.properties"));
    }

    @Test
    void aMissingDataFolderExportsAnEmptyZip() throws IOException {
        Path zip = out.resolve("export.zip");

        List<Path> packed = SettingsExport.write(data.resolve("absent"), zip);

        assertEquals(List.of(), packed);
        assertEquals(List.of(), entries(zip));
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names.stream().sorted().toList();
    }

    private static String contentOf(Path zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " is not in " + zip);
    }
}
