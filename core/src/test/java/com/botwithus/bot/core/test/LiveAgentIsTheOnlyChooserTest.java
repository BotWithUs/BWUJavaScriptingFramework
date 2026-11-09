package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@link LiveAgent} the only test code in the repository that picks a
 * client.
 *
 * <p>Each pattern below answers "which agent?" with "whichever the pipe scan
 * lists first", directly or through a production helper that does. That answer
 * drove a person's own character the moment the harness ran beside their game.
 * A test that reaches for one fails here, with its path named, instead of on
 * someone's account.</p>
 *
 * <p>Scope: every {@code .java} file under each module's non-{@code main}
 * source sets ({@code src/test}, {@code src/sdnLive}, {@code src/preview},
 * ...). Comments and string literals are stripped before matching, so a javadoc
 * that names {@code scanPipes} is not a call and a string cannot hide one.
 * Method references ({@code PipeClient::scanPipes}) are calls.</p>
 *
 * <p>What it cannot see: a production class that scans internally behind an
 * ordinary-looking constructor. {@code AutoStartManager}'s three-argument
 * constructor is the known one, and it connects to every pipe when started.
 * That is fixed where it lives, in production code, not guessed at here by
 * counting constructor arguments.</p>
 */
class LiveAgentIsTheOnlyChooserTest {

    private static final Pattern CHOOSER = Pattern.compile(String.join("|",
            "\\bscanPipes\\b",
            "\\bopenFirstAvailable\\b",
            "\\bdiscoverPids\\b",
            "\\bfirstAvailableOrThrow\\b",
            "\\bnew\\s+(?:[\\w.]+\\.)?PipeClient\\s*\\(\\s*\\)",
            "\\bconnect\\s*\\(\\s*null\\s*\\)",
            "\\bJBotApplication\\s*\\.\\s*main\\s*\\("));

    /** Text block, string, char literal, block comment, line comment: in that order, so none hides another. */
    private static final Pattern NOT_CODE = Pattern.compile(
            "\"\"\"(?s:.*?)\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'|/\\*(?s:.*?)\\*/|//[^\\n]*");

    private static final String SETTINGS_FILE = "settings.gradle.kts";
    private static final String MAIN_SOURCE_SET = "main";
    private static final String SOURCE_DIR = "src";

    /**
     * Repository-relative paths allowed to call a chooser, each for a reason
     * that is not picking an agent: the selector itself, this guard, and a check
     * that scans the launcher's service pipes, which are not agents and are
     * never driven. Paths, not file names: a second {@code LiveAgent.java}
     * elsewhere is not the selector.
     */
    private static final Set<String> ALLOWED = Set.of(
            "core/src/test/java/com/botwithus/bot/core/test/LiveAgent.java",
            "core/src/test/java/com/botwithus/bot/core/test/LiveAgentIsTheOnlyChooserTest.java",
            "core/src/test/java/com/botwithus/bot/core/launcher/LauncherScopeLiveTest.java");

    @Test
    void noTestSourceOutsideTheSelectorChoosesAClient() throws IOException {
        Path root = repositoryRoot();
        List<Path> sourceSets = testSourceSets(root);
        assertTrue(sourceSets.stream().anyMatch(set -> set.endsWith(Path.of("core", "src", "test"))),
                "found no core test sources under " + root + ": " + sourceSets);

        List<String> offenders = sourceSets.stream()
                .flatMap(LiveAgentIsTheOnlyChooserTest::javaFiles)
                .map(file -> relative(root, file))
                .filter(path -> !isAllowed(path))
                .filter(path -> choosesAClient(read(root.resolve(path))))
                .toList();

        assertEquals(List.of(), offenders, "choose the client with LiveAgent.attachOrFail() or "
                + "attachOrSkip(), never by scanning pipes: the first pipe can be someone's own game");
    }

    // ------------------------------------------------- the matcher, without a repository

    @Test
    void matcher_flagsEveryChooserSpelling() {
        List<String> calls = List.of(
                "PipeClient.scanPipes(\"BotWithUs\")",
                "Supplier<List<String>> s = () -> PipeClient.scanPipes(PipeClient.NAME_PREFIX);",
                "Function<String, List<String>> f = PipeClient::scanPipes;",
                "Supplier<List<Long>> p = SharedRegion::discoverPids;",
                "SharedRegion.openFirstAvailable()",
                "String n = PipeClient.firstAvailableOrThrow();",
                "PipeClient pipe = new PipeClient();",
                "PipeClient pipe = new PipeClient( );",
                "Object o = new com.botwithus.bot.core.pipe.PipeClient();",
                "ctx.connect(null);",
                "JBotApplication.main(new String[0]);",
                "JBotApplication . main (args);");
        for (String call : calls) {
            assertTrue(choosesAClient(wrap(call)), "should flag: " + call);
        }
    }

    @Test
    void matcher_passesSelectionThroughLiveAgentAndNamedPipes() {
        List<String> fine = List.of(
                "new PipeClient(LiveAgent.attachOrFail().pipeName())",
                "new PipeClient(\"BotWithUs_test_1\")",
                "ctx.connect(agent.pipeName());",
                "rescanPipesLater();",
                "JBotApplication app = null;");
        for (String code : fine) {
            assertFalse(choosesAClient(wrap(code)), "should pass: " + code);
        }
    }

    @Test
    void matcher_ignoresCommentsAndStrings() {
        String source = String.join("\n",
                "/** Unlike {@link SharedRegion#openFirstAvailable()}, this pins the pid. */",
                "// PipeClient.scanPipes() used to live here",
                "/* new PipeClient() */",
                "String why = \"never scanPipes() or connect(null)\";",
                "String block = \"\"\"",
                "    discoverPids()",
                "    \"\"\";",
                "char c = '\"';",
                "String url = \"https://example.invalid/x\"; // trailing");
        assertFalse(choosesAClient(source));
        assertTrue(choosesAClient(source + "\nPipeClient.scanPipes();"),
                "stripping must not swallow code that follows a string or comment");
    }

    @Test
    void allowlist_isByPathNotFileName() {
        assertTrue(isAllowed("core/src/test/java/com/botwithus/bot/core/test/LiveAgent.java"));
        assertFalse(isAllowed("cli/src/test/java/com/botwithus/bot/cli/LiveAgent.java"));
        assertFalse(isAllowed("core/src/test/java/com/botwithus/bot/core/impl/LauncherScopeLiveTest.java"));
    }

    // ------------------------------------------------------------------ helpers

    static boolean choosesAClient(String source) {
        String code = NOT_CODE.matcher(source).replaceAll(LiveAgentIsTheOnlyChooserTest::blankOut);
        return CHOOSER.matcher(code).find();
    }

    static boolean isAllowed(String repositoryRelativePath) {
        return ALLOWED.contains(repositoryRelativePath);
    }

    /** Keeps an emptied string a string, so the code around it still parses as code. */
    private static String blankOut(MatchResult match) {
        return match.group().startsWith("\"") ? "\"\"" : " ";
    }

    private static String wrap(String statement) {
        return "class Planted {\n    void run() {\n        " + statement + "\n    }\n}\n";
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(SETTINGS_FILE))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no " + SETTINGS_FILE + " above " + Path.of("").toAbsolutePath());
        return dir;
    }

    /** {@code <module>/src/<set>} for every top-level module and every set but {@code main}. */
    private static List<Path> testSourceSets(Path root) throws IOException {
        try (Stream<Path> modules = Files.list(root)) {
            return modules
                    .map(module -> module.resolve(SOURCE_DIR))
                    .filter(Files::isDirectory)
                    .flatMap(LiveAgentIsTheOnlyChooserTest::listDirectories)
                    .filter(set -> !set.getFileName().toString().equals(MAIN_SOURCE_SET))
                    .toList();
        }
    }

    private static Stream<Path> listDirectories(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<Path> javaFiles(Path sourceSet) {
        try {
            return Files.walk(sourceSet).filter(file -> file.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
