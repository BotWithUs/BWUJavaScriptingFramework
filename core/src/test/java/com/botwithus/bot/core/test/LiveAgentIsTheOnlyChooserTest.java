package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
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
 * <p>Scope: every {@code .java} file under each top-level module's non-{@code
 * main} source sets ({@code src/test}, {@code src/sdnLive}, {@code src/preview},
 * ...), plus the {@code main} sets of the modules that are test code
 * ({@link #TEST_ONLY_MODULES}). Unicode escapes are decoded first, as javac
 * does. Comments and string literals are then stripped, so a javadoc that names
 * {@code scanPipes} is not a call and a string cannot hide one. Method
 * references ({@code PipeClient::scanPipes}, {@code JBotApplication::main}) are
 * calls.</p>
 *
 * <p>The every-agent helpers the host itself needs are named so they can be
 * seen: {@code AgentPin.scannerFromEnvironment} (every agent when unpinned),
 * {@code AgentPin.scannerKeeping} (whatever its predicate allows),
 * {@code AgentPin.visiblePipes} (every agent pipe name) and
 * {@code AutoStartManager.connectingToEveryAgent}. Each is flagged in test code,
 * and a file may be allowed one specific call by path ({@link #ALLOWED_CALLS}),
 * never a blanket exemption.</p>
 *
 * <p>What it cannot see, and why that is acceptable:</p>
 * <ul>
 *   <li>A production class that scans behind an ordinary constructor or method
 *       not listed here. The rule is enforced at the source instead: auto-connect
 *       scans through {@code AgentPin}, the developer entry points pin or refuse,
 *       and the first-pipe APIs ({@code new PipeClient()},
 *       {@code firstAvailableOrThrow}, {@code openFirstAvailable},
 *       {@code discoverPids}) no longer exist.</li>
 *   <li>Reflection ({@code getMethod("scanPipes", ...)}): the name is a string, and
 *       strings are stripped. Reflection is banned in this codebase anyway.</li>
 *   <li>The pure {@code AgentPin.keeping(predicate, scanner)} over a scanner the
 *       caller wrote itself: whatever that scanner lists is the caller's own.</li>
 *   <li>A script module registered through {@code additionalScripts.txt} at a
 *       nested path: only top-level modules are walked. None exists today.</li>
 *   <li>Test code in the {@code main} set of a module not listed in
 *       {@link #TEST_ONLY_MODULES}.</li>
 * </ul>
 */
class LiveAgentIsTheOnlyChooserTest {

    private static final Pattern CHOOSER = Pattern.compile(String.join("|",
            "\\bscanPipes\\b",
            "\\bopenFirstAvailable\\b",
            "\\bdiscoverPids\\b",
            "\\bfirstAvailableOrThrow\\b",
            "\\bnew\\s+(?:[\\w.]+\\.)?PipeClient\\s*\\(\\s*\\)",
            "\\bconnect\\s*\\(\\s*null\\s*\\)",
            "\\b(?:JBotApplication|SnapshotProbe|EventPumpProbe)\\s*(?:\\.|::)\\s*main\\b",
            "\\bscannerFromEnvironment\\b",
            "\\bscannerKeeping\\b",
            "\\bvisiblePipes\\b",
            "\\bconnectingToEveryAgent\\b"));

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** A unicode escape as javac reads it: a backslash, one or more {@code u}s, four hex digits. */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+([0-9a-fA-F]{4})");

    /** Modules whose {@code main} source set is test code, so it is walked too. */
    private static final Set<String> TEST_ONLY_MODULES = Set.of("test-support");

    /** Text block, string, char literal, block comment, line comment: in that order, so none hides another. */
    private static final Pattern NOT_CODE = Pattern.compile(
            "\"\"\"(?s:.*?)\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'|/\\*(?s:.*?)\\*/|//[^\\n]*");

    private static final String SETTINGS_FILE = "settings.gradle.kts";
    private static final String MAIN_SOURCE_SET = "main";
    private static final String SOURCE_DIR = "src";
    private static final int HEX = 16;

    /** This guard, which names every pattern in its own tests. The only whole-file exemption. */
    private static final String SELF =
            "core/src/test/java/com/botwithus/bot/core/test/LiveAgentIsTheOnlyChooserTest.java";

    /**
     * One specific call each, by repository-relative path, each for a reason
     * that is not picking an agent by itself:
     * <ul>
     *   <li>{@code LiveAgent} lists pipe names to hand to {@code AgentPin}'s rule;</li>
     *   <li>{@code LauncherLiveTest} keeps only the clients it launched;</li>
     *   <li>{@code LauncherScopeLiveTest} scans the launcher's service pipes,
     *       which are not agents and are never driven.</li>
     * </ul>
     * Any other chooser call in those files still fails.
     */
    private static final Map<String, Set<String>> ALLOWED_CALLS = Map.of(
            "core/src/test/java/com/botwithus/bot/core/test/LiveAgent.java", Set.of("visiblePipes"),
            "cli/src/test/java/com/botwithus/bot/cli/LauncherLiveTest.java", Set.of("scannerKeeping"),
            "core/src/test/java/com/botwithus/bot/core/launcher/LauncherScopeLiveTest.java", Set.of("scanPipes"));

    @Test
    void noTestSourceOutsideTheSelectorChoosesAClient() throws IOException {
        Path root = repositoryRoot();
        List<Path> sourceSets = testSourceSets(root);
        assertTrue(sourceSets.stream().anyMatch(set -> set.endsWith(Path.of("core", "src", "test"))),
                "found no core test sources under " + root + ": " + sourceSets);

        List<String> offenders = sourceSets.stream()
                .flatMap(LiveAgentIsTheOnlyChooserTest::javaFiles)
                .map(file -> relative(root, file))
                .filter(path -> !path.equals(SELF))
                .filter(path -> !disallowedCalls(path, read(root.resolve(path))).isEmpty())
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
                "JBotApplication . main (args);",
                "Consumer<String[]> m = JBotApplication::main;",
                "SnapshotProbe.main(new String[0]);",
                "EventPumpProbe.main(new String[0]);",
                "Runnable r = () -> SnapshotProbe . main(args);",
                "PipeClient.\\u0073canPipes(\"BotWithUs\");",
                "PipeClient.\\uuu0073canPipes(\"BotWithUs\");");
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
    void allowance_isOneCallByPath_notAFileExemption() {
        String launcherLive = "cli/src/test/java/com/botwithus/bot/cli/LauncherLiveTest.java";
        String keeps = wrap("Object s = AgentPin.scannerKeeping(this::isLaunchedHere);");
        String scans = wrap("Object s = PipeClient.scanPipes(\"BotWithUs\");");

        assertEquals(List.of(), disallowedCalls(launcherLive, keeps), "its one allowed call");
        assertEquals(List.of("scanPipes"), disallowedCalls(launcherLive, scans), "any other call still fails");
        assertEquals(List.of("scannerKeeping"),
                disallowedCalls("cli/src/test/java/com/botwithus/bot/cli/Elsewhere.java", keeps),
                "the same call elsewhere fails");
        assertEquals(List.of("scanPipes"), disallowedCalls(
                "core/src/test/java/com/botwithus/bot/core/impl/LauncherScopeLiveTest.java", scans),
                "a same-named file at another path is not allowed");
    }

    @Test
    void matcher_flagsThisPrsOwnEveryAgentHelpers() {
        List<String> calls = List.of(
                "Object s = AgentPin.scannerFromEnvironment();",
                "Object s = AgentPin.scannerKeeping(pid -> true);",
                "Object s = AgentPin.visiblePipes();",
                "Supplier<List<String>> s = AgentPin::visiblePipes;",
                "Object m = AutoStartManager.connectingToEveryAgent(ctx, store, settings);");
        for (String call : calls) {
            assertTrue(choosesAClient(wrap(call)), "should flag: " + call);
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean choosesAClient(String source) {
        return !chooserCalls(source).isEmpty();
    }

    /** The chooser calls in {@code source} that {@link #ALLOWED_CALLS} does not allow at {@code path}. */
    static List<String> disallowedCalls(String path, String source) {
        Set<String> allowed = ALLOWED_CALLS.getOrDefault(path, Set.of());
        return chooserCalls(source).stream().filter(call -> !allowed.contains(call)).toList();
    }

    /** Each chooser call in {@code source}, named by its leading identifier. */
    private static List<String> chooserCalls(String source) {
        String decoded = UNICODE_ESCAPE.matcher(source).replaceAll(LiveAgentIsTheOnlyChooserTest::decodeEscape);
        String code = NOT_CODE.matcher(decoded).replaceAll(LiveAgentIsTheOnlyChooserTest::blankOut);
        return CHOOSER.matcher(code).results().map(LiveAgentIsTheOnlyChooserTest::callName).toList();
    }

    /** {@code scanPipes}, {@code new PipeClient()}, {@code JBotApplication.main}: the match's first word. */
    private static String callName(MatchResult match) {
        Matcher word = IDENTIFIER.matcher(match.group());
        return word.find() ? word.group() : match.group();
    }

    private static String decodeEscape(MatchResult match) {
        return Matcher.quoteReplacement(Character.toString(Integer.parseInt(match.group(1), HEX)));
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

    /**
     * {@code <module>/src/<set>} for every top-level module and every set but
     * {@code main}, and {@code main} too for {@link #TEST_ONLY_MODULES}.
     */
    private static List<Path> testSourceSets(Path root) throws IOException {
        try (Stream<Path> modules = Files.list(root)) {
            return modules
                    .map(module -> module.resolve(SOURCE_DIR))
                    .filter(Files::isDirectory)
                    .flatMap(LiveAgentIsTheOnlyChooserTest::listDirectories)
                    .filter(LiveAgentIsTheOnlyChooserTest::holdsTestCode)
                    .toList();
        }
    }

    private static boolean holdsTestCode(Path sourceSet) {
        return !sourceSet.getFileName().toString().equals(MAIN_SOURCE_SET)
                || TEST_ONLY_MODULES.contains(sourceSet.getParent().getParent().getFileName().toString());
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
