package com.botwithus.bot.core.runlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogRetentionTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void specBounds() {
        assertEquals(20, RunLogRetention.MAX_FILES_PER_SCRIPT);
        assertEquals(200L * 1024 * 1024, RunLogRetention.MAX_TREE_BYTES);
    }

    @Test
    void perScript_keepsTheNewest19_leavingRoomForTheNewRun() throws Exception {
        Path script = root.resolve("woodcutter");
        for (int i = 0; i < 25; i++) {
            write(script.resolve("f" + i + ".log"), 10, i);
        }
        Files.writeString(script.resolve("notes.txt"), "not a log");
        new RunLogRetention().beforeNewFile(root, script, Set.of());
        List<String> left = names(script);
        assertAll(
                () -> assertEquals(RunLogRetention.MAX_FILES_PER_SCRIPT - 1 + 1, left.size()),
                () -> assertTrue(left.contains("notes.txt"), "only .log files are pruned"),
                () -> assertFalse(left.contains("f5.log"), "oldest go first"),
                () -> assertTrue(left.contains("f6.log")),
                () -> assertTrue(left.contains("f24.log")));
    }

    @Test
    void wholeTree_isCappedAcrossScripts_oldestFirst() throws Exception {
        write(root.resolve("a/old.log"), 100, 0);
        write(root.resolve("b/mid.log"), 100, 1);
        write(root.resolve("a/new.log"), 100, 2);
        new RunLogRetention(RunLogRetention.MAX_FILES_PER_SCRIPT, 250)
                .beforeNewFile(root, root.resolve("c"), Set.of());
        assertAll(
                () -> assertFalse(Files.exists(root.resolve("a/old.log"))),
                () -> assertTrue(Files.exists(root.resolve("b/mid.log"))),
                () -> assertTrue(Files.exists(root.resolve("a/new.log"))));
    }

    @Test
    void anOpenFile_isNeverDeleted() throws Exception {
        Path script = root.resolve("s");
        Path open = script.resolve("oldest-but-open.log");
        write(open, 10, 0);
        write(script.resolve("b.log"), 10, 1);
        write(script.resolve("c.log"), 10, 2);
        new RunLogRetention(2, Long.MAX_VALUE)
                .beforeNewFile(root, script, Set.of(open.toAbsolutePath().normalize()));
        assertAll(
                () -> assertTrue(Files.exists(open)),
                () -> assertFalse(Files.exists(script.resolve("b.log"))),
                () -> assertTrue(Files.exists(script.resolve("c.log"))));
    }

    @Test
    void aMissingRoot_isNotAnError() {
        new RunLogRetention().beforeNewFile(root.resolve("absent"), root.resolve("absent/s"), Set.of());
    }

    private static void write(Path file, int bytes, int ageRank) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.from(BASE.plusSeconds(ageRank)));
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }
}
