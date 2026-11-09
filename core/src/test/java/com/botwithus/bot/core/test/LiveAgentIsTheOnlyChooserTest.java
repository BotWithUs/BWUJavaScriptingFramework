package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@link LiveAgent} the only code in this test tree that picks a client.
 *
 * <p>Each of these calls answers "which agent?" with "whichever the pipe scan
 * lists first", and that answer drove a person's own character the moment the
 * harness ran beside their game. A new live test that reaches for one of them
 * fails here, with the file named, instead of on someone's account.</p>
 */
class LiveAgentIsTheOnlyChooserTest {

    /** Gradle runs a module's tests from the module directory. */
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    private static final List<String> CHOOSERS = List.of(
            "scanPipes(", "openFirstAvailable(", "discoverPids(", "firstAvailableOrThrow(");

    /**
     * Files allowed to call a chooser, each for a reason that is not picking
     * an agent: the selector itself, this guard, and a check that scans the
     * launcher's service pipes, which are not agents and are never driven.
     */
    private static final Set<String> ALLOWED = Set.of(
            "LiveAgent.java", "LiveAgentIsTheOnlyChooserTest.java", "LauncherScopeLiveTest.java");

    @Test
    void noTestOutsideTheSelectorChoosesAClientByScanning() throws IOException {
        assertTrue(Files.isDirectory(TEST_SOURCES), "run from the core module: " + TEST_SOURCES.toAbsolutePath());
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            List<String> offenders = files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !ALLOWED.contains(file.getFileName().toString()))
                    .filter(LiveAgentIsTheOnlyChooserTest::callsAChooser)
                    .map(file -> TEST_SOURCES.relativize(file).toString())
                    .toList();

            assertEquals(List.of(), offenders, "choose the client with LiveAgent.attachOrFail() or "
                    + "attachOrSkip(), never by scanning pipes: the first pipe can be someone's own game");
        }
    }

    private static boolean callsAChooser(Path file) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            return CHOOSERS.stream().anyMatch(source::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
