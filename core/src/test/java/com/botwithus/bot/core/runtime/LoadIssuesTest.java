package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadIssuesTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final Instant OLDER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-02-01T00:00:00Z");

    @TempDir
    Path dir;

    private final LoadIssues issues = new LoadIssues(Clock.fixed(NOW, ZoneOffset.UTC));

    @ScriptManifest(name = "Woodcutter")
    static final class Woodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    /** A different class declaring the same manifest name — an older build under a new package. */
    @ScriptManifest(name = "Woodcutter")
    static final class RenamedWoodcutter implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    @ScriptManifest(name = "Fletcher")
    static final class Fletcher implements BotScript {
        @Override public void onStart(ScriptContext ctx) { }
        @Override public int onLoop() { return -1; }
        @Override public void onStop() { }
    }

    private Path jar(String name, Instant modified) throws IOException {
        Path jar = Files.write(dir.resolve(name), new byte[0]);
        Files.setLastModifiedTime(jar, FileTime.from(modified));
        return jar;
    }

    private static ScriptLoadResult failed(Path jar, String message) {
        return ScriptLoadResult.failure(jar, new IllegalStateException(message), List.of());
    }

    private static ScriptLoadResult loaded(Path jar, BotScript script) {
        return ScriptLoadResult.success(jar, script, List.of());
    }

    @Test
    void aFailedJar_isListedWithItsError_timeAndFolder() throws IOException {
        Path jar = jar("broken.jar", OLDER);
        ScriptLoadResult result = failed(jar, "boom");

        issues.record(ScriptFolder.SCRIPTS, List.of(result));

        LoadIssue.Failed entry = issues.failureOf(jar).orElseThrow();
        assertAll(
                () -> assertEquals(List.of(entry), issues.issues()),
                () -> assertSame(result.error().orElseThrow(), entry.error()),
                () -> assertEquals(ScriptFolder.SCRIPTS, entry.folder()),
                () -> assertEquals(Optional.of(OLDER), entry.lastModified()),
                () -> assertEquals(NOW, entry.recordedAt()));
    }

    @Test
    void aFailure_isClearedWhenThatJarLoadsCleanly() throws IOException {
        Path jar = jar("fixed.jar", OLDER);
        issues.record(ScriptFolder.SCRIPTS, List.of(failed(jar, "boom")));

        issues.record(ScriptFolder.SCRIPTS, List.of(loaded(jar, new Woodcutter())));

        assertTrue(issues.issues().isEmpty());
    }

    @Test
    void aSecondFailure_replacesTheFirst() throws IOException {
        Path jar = jar("broken.jar", OLDER);
        issues.record(ScriptFolder.SCRIPTS, List.of(failed(jar, "first")));

        issues.record(ScriptFolder.SCRIPTS, List.of(failed(jar, "second")));

        assertEquals(1, issues.issues().size());
        assertEquals("second", issues.failureOf(jar).orElseThrow().error().getMessage());
    }

    @Test
    void aFailure_outlivesPassesThatDoNotLoadThatJar() throws IOException {
        Path broken = jar("broken.jar", OLDER);
        Path other = jar("other.jar", OLDER);
        issues.record(ScriptFolder.SCRIPTS, List.of(failed(broken, "boom")));

        issues.record(ScriptFolder.SCRIPTS, List.of(loaded(other, new Fletcher())));
        issues.record(ScriptFolder.MANAGEMENT, List.of());

        assertTrue(issues.failureOf(broken).isPresent(), "cumulative, not only the latest pass");
    }

    @Test
    void aFailure_isDroppedOnceItsJarIsDeleted() throws IOException {
        Path jar = jar("deleted.jar", OLDER);
        issues.record(ScriptFolder.SCRIPTS, List.of(failed(jar, "boom")));
        Files.delete(jar);

        issues.record(ScriptFolder.SCRIPTS, List.of());

        assertTrue(issues.issues().isEmpty());
    }

    @Test
    void aPassOverOneFolder_leavesTheOtherFoldersEntriesAlone() throws IOException {
        Path jar = jar("broken.jar", OLDER);
        issues.record(ScriptFolder.MANAGEMENT, List.of(failed(jar, "boom")));
        Files.delete(jar);

        issues.record(ScriptFolder.SCRIPTS, List.of());

        assertEquals(1, issues.issues(ScriptFolder.MANAGEMENT).size());
        assertTrue(issues.issues(ScriptFolder.SCRIPTS).isEmpty());
    }

    @Test
    void twoJarsDeclaringOneScriptName_warnAboutTheOlderJar() throws IOException {
        Path older = jar("woodcutter-old.jar", OLDER);
        Path newer = jar("woodcutter-new.jar", NEWER);

        issues.record(ScriptFolder.SCRIPTS, List.of(
                loaded(older, new RenamedWoodcutter()), loaded(newer, new Woodcutter())));

        assertEquals(List.of(new LoadIssue.DuplicateName(
                        ScriptFolder.SCRIPTS, older, "Woodcutter", newer, Optional.of(OLDER))),
                issues.issues());
    }

    @Test
    void theDuplicateWarning_goesAwayWhenOneJarRemains() throws IOException {
        Path older = jar("woodcutter-old.jar", OLDER);
        Path newer = jar("woodcutter-new.jar", NEWER);
        issues.record(ScriptFolder.SCRIPTS, List.of(
                loaded(older, new RenamedWoodcutter()), loaded(newer, new Woodcutter())));

        issues.record(ScriptFolder.SCRIPTS, List.of(loaded(newer, new Woodcutter())));

        assertTrue(issues.issues().isEmpty());
    }

    @Test
    void oneJarProvidingTwoScriptsOfOneName_isNotADuplicateJar() throws IOException {
        Path jar = jar("bundle.jar", OLDER);

        issues.record(ScriptFolder.SCRIPTS, List.of(
                loaded(jar, new Woodcutter()), loaded(jar, new RenamedWoodcutter())));

        assertTrue(issues.issues().isEmpty());
    }
}
