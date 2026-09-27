package com.botwithus.bot.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the build's test home: {@code cli/build.gradle.kts} points
 * {@code user.home} at an empty folder under the build directory for every test
 * run, so a test that reaches a default path, such as {@code ~/.botwithus/groups.json},
 * writes there and never over the user's own files. Fails if that setting is lost.
 */
class TestHomeTest {

    private static final String TEST_HOME = "test-home";

    @Test
    void testsRunWithAHomeOfTheirOwn_underTheBuildDirectory() {
        Path home = Path.of(System.getProperty("user.home"));

        assertEquals(TEST_HOME, home.getFileName().toString(), () -> "user.home is " + home);
        assertEquals("build", home.getParent().getFileName().toString(), () -> "user.home is " + home);
        assertTrue(Files.isDirectory(home), () -> home + " does not exist");
    }
}
