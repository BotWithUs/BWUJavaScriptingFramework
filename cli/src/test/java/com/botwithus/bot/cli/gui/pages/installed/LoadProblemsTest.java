package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.core.runtime.LoadIssue;
import com.botwithus.bot.core.runtime.ScriptFolder;
import com.botwithus.bot.core.runtime.ScriptLoadResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The page's "Failed to load" entries, from the host's failed-load list. */
class LoadProblemsTest {

    private static final Instant OLD = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant NEW = Instant.parse("2026-09-12T10:00:00Z");
    private static final Path OLD_JAR = Path.of("scripts", "woodcutting-1.0-SNAPSHOT.jar");
    private static final Path NEW_JAR = Path.of("scripts", "woodcutting-2.0.jar");
    private static final String FOLDER = "scripts/";

    @ScriptManifest(name = "Woodcutting")
    static final class Woodcutting implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    private static ScriptLoadResult loaded(Path jar, Instant modified) {
        return new ScriptLoadResult(jar, Optional.of(new Woodcutting()), Optional.empty(), List.of(),
                Optional.of(modified));
    }

    private static LoadIssue.Failed failed(Path jar, Instant modified) {
        return new LoadIssue.Failed(ScriptFolder.SCRIPTS, jar,
                new ServiceConfigurationError("BotScript provider not found"), Optional.of(modified), NEW);
    }

    @Test
    void aFailure_showsItsErrorOnOneLine_andKeepsTheTrace() {
        LoadProblem p = LoadProblems.of(List.of(failed(OLD_JAR, OLD)), List.of(), FOLDER).getFirst();

        assertEquals(LoadProblem.Kind.FAILED, p.kind());
        assertEquals("woodcutting-1.0-SNAPSHOT.jar", p.jarName());
        assertEquals("ServiceConfigurationError: BotScript provider not found", p.message());
        assertTrue(p.stackTrace().startsWith("java.util.ServiceConfigurationError: BotScript provider not found"),
                p.stackTrace());
        assertTrue(p.stackTrace().contains("\tat "), "the frames are kept");
        assertEquals(Optional.empty(), p.hint());
    }

    @Test
    void aFailedOlderBuildOfAScriptThatLoaded_getsTheDuplicateHint() {
        LoadProblem p = LoadProblems.of(List.of(failed(OLD_JAR, OLD)), List.of(loaded(NEW_JAR, NEW)), FOLDER)
                .getFirst();

        assertEquals(Optional.of("An older build of Woodcutting sits next to woodcutting-2.0.jar. "
                + "Delete it from scripts/ or fix its module-info provides line."), p.hint());
    }

    @Test
    void aFailedNewerBuild_isNotCalledTheOlderOne() {
        LoadProblem p = LoadProblems.of(List.of(failed(NEW_JAR, NEW)), List.of(loaded(OLD_JAR, OLD)), FOLDER)
                .getFirst();

        assertEquals(Optional.empty(), p.hint());
    }

    @Test
    void anOlderDuplicate_isAWarning_thatNamesTheJarThatRuns() {
        LoadIssue.DuplicateName dup = new LoadIssue.DuplicateName(ScriptFolder.SCRIPTS, OLD_JAR, "Woodcutting",
                NEW_JAR, Optional.of(OLD));

        LoadProblem p = LoadProblems.of(List.of(dup), List.of(), FOLDER).getFirst();

        assertEquals(LoadProblem.Kind.OLDER_DUPLICATE, p.kind());
        assertEquals("Older copy of Woodcutting: woodcutting-2.0.jar is the one that runs", p.message());
        assertEquals(Optional.of("Delete it from scripts/ to clear this."), p.hint());
        assertTrue(p.stackTrace().isEmpty());
    }

    @Test
    void everyIssue_inTheListsOrder() {
        LoadIssue.DuplicateName dup = new LoadIssue.DuplicateName(ScriptFolder.SCRIPTS, OLD_JAR, "Woodcutting",
                NEW_JAR, Optional.of(OLD));
        Path broken = Path.of("scripts", "broken.jar");

        List<LoadProblem> problems = LoadProblems.of(List.of(failed(broken, NEW), dup), List.of(), FOLDER);

        assertEquals(List.of("broken.jar", "woodcutting-1.0-SNAPSHOT.jar"),
                problems.stream().map(LoadProblem::jarName).toList());
    }
}
