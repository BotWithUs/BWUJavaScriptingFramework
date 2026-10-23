package com.botwithus.bot.core.runlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogFileTest {

    private static final long SMALL_CAP = 200;

    @TempDir
    Path dir;

    @Test
    void lines_areRedacted_utf8_andLfOnly() throws Exception {
        Path path = dir.resolve("s/run.log");
        try (RunLogFile file = RunLogFile.create(path, Redactor.withNames(
                new KnownNames(List.of(), List.of("Zezima"))))) {
            file.writeLines("hello Zezima\r\nsecond ✓ line");
        }
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals("hello Player#1\nsecond ✓ line\n", text),
                () -> assertFalse(text.contains("\r")));
    }

    @Test
    void reachingTheCap_writesTheMarkerOnce_dropsLaterLines_butNotACrash() throws Exception {
        Path path = dir.resolve("capped.log");
        try (RunLogFile file = RunLogFile.create(path, Redactor.withNames(KnownNames.NONE), SMALL_CAP)) {
            for (int i = 0; i < 50; i++) {
                file.writeLines("line " + i + " padding padding");
            }
            file.writeRedacted(List.of("=== CRASH phase=on_loop iteration=1 at=x ===", "=== END ==="));
            file.writeLines("after the crash");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        long markers = lines.stream().filter(RunLogFile.TRUNCATED_MARKER::equals).count();
        int markerAt = lines.indexOf(RunLogFile.TRUNCATED_MARKER);
        assertAll(
                () -> assertEquals(1, markers),
                () -> assertTrue(Files.size(path) - lines.subList(markerAt + 1, lines.size()).stream()
                        .mapToLong(l -> l.length() + 1).sum() <= SMALL_CAP, "cap holds up to the marker"),
                () -> assertEquals("=== CRASH phase=on_loop iteration=1 at=x ===", lines.get(markerAt + 1)),
                () -> assertEquals("=== END ===", lines.getLast()),
                () -> assertFalse(lines.contains("after the crash")));
    }

    @Test
    void theSpecCap_isFiveMegabytes() {
        assertEquals(5L * 1024 * 1024, RunLogFile.MAX_BYTES);
        assertEquals("[log truncated at 5 MB]", RunLogFile.TRUNCATED_MARKER);
    }

    @Test
    void afterClose_writesAreDropped() throws Exception {
        Path path = dir.resolve("closed.log");
        RunLogFile file = RunLogFile.create(path, Redactor.withNames(KnownNames.NONE));
        file.writeLines("one");
        file.close();
        file.writeLines("two");
        assertEquals(List.of("one"), Files.readAllLines(path));
    }

    @Test
    void anExistingFile_isNeverOverwritten() throws Exception {
        Path path = dir.resolve("exists.log");
        Files.writeString(path, "keep");
        assertThrows(Exception.class, () -> RunLogFile.create(path, Redactor.withNames(KnownNames.NONE)));
        assertEquals("keep", Files.readString(path));
    }
}
