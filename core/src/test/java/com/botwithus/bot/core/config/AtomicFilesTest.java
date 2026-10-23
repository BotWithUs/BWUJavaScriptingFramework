package com.botwithus.bot.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFilesTest {

    private static final String OLD = "{\"old\":true,\"padding\":\"so a torn write would show\"}";
    private static final String NEW = "{\"new\":true}";

    @TempDir
    Path dir;

    private List<Path> filesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }

    @Test
    void write_createsTheFileAndItsDirectory() throws IOException {
        Path target = dir.resolve("nested").resolve("state.json");

        AtomicFiles.write(target, NEW.getBytes(StandardCharsets.UTF_8));

        assertEquals(NEW, Files.readString(target));
    }

    @Test
    void write_replacesAnExistingFileAndLeavesNothingElseBehind() throws IOException {
        Path target = dir.resolve("state.json");
        Files.writeString(target, OLD);

        AtomicFiles.write(target, NEW.getBytes(StandardCharsets.UTF_8));

        assertEquals(NEW, Files.readString(target));
        assertEquals(List.of(target), filesIn(dir), "no temporary file may be left behind");
    }

    /**
     * The failure this class exists for: the new contents stop part-way, as they
     * would when the process dies or the disk fills. Writing in place would leave
     * the target truncated to that half.
     */
    @Test
    void write_contentFailsPartWay_leavesTheOldFileWholeAndNoTemporaryFile() throws IOException {
        Path target = dir.resolve("state.json");
        Files.writeString(target, OLD);

        IOException thrown = assertThrows(IOException.class, () -> AtomicFiles.write(target, out -> {
            out.write("{\"half".getBytes(StandardCharsets.UTF_8));
            throw new IOException("disk full");
        }));

        assertEquals("disk full", thrown.getMessage());
        assertEquals(OLD, Files.readString(target));
        assertEquals(List.of(target), filesIn(dir), "the half-written temporary file must be removed");
    }

    /**
     * Windows refuses to rename over a file another handle has open, which is what
     * a second host reading the file at that moment looks like. The write must
     * fail loudly rather than fall back to rewriting the file in place, which
     * would hand that reader a torn file.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void write_targetHeldOpenByAReader_failsWithoutTouchingIt() throws IOException {
        Path target = dir.resolve("state.json");
        Files.writeString(target, OLD);

        try (InputStream reader = Files.newInputStream(target)) {
            assertThrows(IOException.class,
                    () -> AtomicFiles.write(target, NEW.getBytes(StandardCharsets.UTF_8)));
            assertEquals(OLD, new String(reader.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(OLD, Files.readString(target));
        assertEquals(List.of(target), filesIn(dir));
    }
}
