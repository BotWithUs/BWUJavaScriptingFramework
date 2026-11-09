package com.botwithus.bot.core.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * and a file may be allowed specific, counted calls by path ({@link #ALLOWED_CALLS}),
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
 *   <li>The body of a predicate an allowed call names. The allowance pins
 *       {@code AgentPin.scannerKeeping(this::isLaunchedHere)} by the method's
 *       <em>name</em>; widening {@code isLaunchedHere} to {@code return true}
 *       would make {@code LauncherLiveTest}'s auto-connect keep every agent, and
 *       this guard would not see it. Review that method as part of any change
 *       to it.</li>
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
    private static final Pattern NOT_NEWLINE = Pattern.compile("[^\\n]");
    private static final int TEXT_BLOCK_QUOTES = 3;

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
     * One chooser call that a file may make, pinned to the exact line it is on:
     * trimmed, comments removed, <em>string contents kept</em>. Pinning to the
     * line, not just the call's name, is what stops a spare allowance from
     * covering a different call: removing an allowed call and adding another in
     * the same edit leaves the count unchanged, but the new line matches no
     * allowance. Keeping the strings matters because an allowance's reason can
     * live in its argument: {@code LauncherScopeLiveTest} may scan the launcher's
     * service pipes, and the same call with an agent prefix is the very call
     * this guard bans.
     */
    record Allowance(String call, String line) {

        @Override
        public String toString() {
            return call + " @ " + line;
        }
    }

    /** One chooser call found in a file: its name and the line it is on, as above. */
    private record Call(String name, String line) {
    }

    /**
     * The exact chooser calls each file may make, by repository-relative path.
     * Each allowance is spent by one call on its line; any other chooser call in
     * the file fails, and so does an allowance no call spends
     * ({@link #everyAllowanceIsSpent_soAStaleOneCannotCoverANewCall}). Each is for
     * a reason that is not picking an agent by itself:
     * <ul>
     *   <li>{@code LiveAgent} lists pipe names to hand to {@code AgentPin}'s rule,
     *       once in each of its two no-arg entry points;</li>
     *   <li>{@code LauncherLiveTest} keeps only the clients it launched;</li>
     *   <li>{@code LauncherScopeLiveTest} scans the launcher's service pipes,
     *       which are not agents and are never driven.</li>
     * </ul>
     * Changing one of these lines means changing its allowance in the same edit.
     */
    private static final Map<String, List<Allowance>> ALLOWED_CALLS = Map.of(
            "core/src/test/java/com/botwithus/bot/core/test/LiveAgent.java", List.of(
                    new Allowance("visiblePipes", "return attachOrFail(System::getProperty, AgentPin::visiblePipes);"),
                    new Allowance("visiblePipes", "return attachOrSkip(System::getProperty, AgentPin::visiblePipes);")),
            "cli/src/test/java/com/botwithus/bot/cli/LauncherLiveTest.java", List.of(
                    new Allowance("scannerKeeping", "AgentPin.scannerKeeping(this::isLaunchedHere));")),
            "core/src/test/java/com/botwithus/bot/core/launcher/LauncherScopeLiveTest.java", List.of(
                    new Allowance("scanPipes",
                            "List<String> real = PipeClient.scanPipes(\"bwu_svc_auto_\").stream()")));

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

    /**
     * Every allowance is spent exactly. An allowance left over is not harmless:
     * if one of the allowed calls is removed, the spare would silently cover a
     * new, different call added later. So the table must match the files, and
     * removing an allowed call means removing its allowance in the same change.
     */
    @Test
    void everyAllowanceIsSpent_soAStaleOneCannotCoverANewCall() {
        Path root = repositoryRoot();
        Map<String, List<Allowance>> stale = new TreeMap<>();
        ALLOWED_CALLS.keySet().forEach(path -> {
            List<Allowance> unspent = unspentAllowances(path, read(root.resolve(path)));
            if (!unspent.isEmpty()) {
                stale.put(path, unspent);
            }
        });

        assertEquals(Map.of(), stale, "allowances whose line no call is on any more: update or remove them");
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
        String keepsLine = "AgentPin.scannerKeeping(this::isLaunchedHere));";
        String keeps = wrap(keepsLine);
        String scans = wrap("Object s = PipeClient.scanPipes(\"BotWithUs\");");

        assertEquals(List.of(), disallowedCalls(launcherLive, keeps), "its one allowed call, on its line");
        String keepsTwice = wrap(keepsLine + "\n        Object b = AgentPin.scannerKeeping(pid -> true);");
        assertEquals(List.of("scannerKeeping"), disallowedCalls(launcherLive, keepsTwice),
                "a second use of an allowed call is counted against it, not waved through");
        assertEquals(List.of("scannerKeeping"),
                disallowedCalls(launcherLive, wrap("Object s = AgentPin.scannerKeeping(pid -> true);")),
                "the allowed call on a different line is a different call");
        assertEquals(List.of("scanPipes"), disallowedCalls(launcherLive, scans), "any other call still fails");
        assertEquals(List.of("scannerKeeping"),
                disallowedCalls("cli/src/test/java/com/botwithus/bot/cli/Elsewhere.java", keeps),
                "the same call elsewhere fails");
        assertEquals(List.of("scanPipes"), disallowedCalls(
                "core/src/test/java/com/botwithus/bot/core/impl/LauncherScopeLiveTest.java", scans),
                "a same-named file at another path is not allowed");
    }

    /**
     * The reviewer's plant P8: {@code LauncherScopeLiveTest}'s allowed scan keeps
     * its shape but its prefix changes from the launcher's service pipes to the
     * agent prefix, turning the allowed call into the exact call this guard
     * bans. The allowance pins the string, so the changed call is disallowed and
     * the allowance is left unspent.
     */
    @Test
    void allowance_pinsStringArguments_soAChangedPrefixIsCaught() {
        String scope = "core/src/test/java/com/botwithus/bot/core/launcher/LauncherScopeLiveTest.java";
        String allowed = wrap("List<String> real = PipeClient.scanPipes(\"bwu_svc_auto_\").stream()");
        String agentPrefix = wrap("List<String> real = PipeClient.scanPipes(\"BotWithUs_\").stream()");

        assertEquals(List.of(), disallowedCalls(scope, allowed));
        assertEquals(List.of(), unspentAllowances(scope, allowed));
        assertEquals(List.of("scanPipes"), disallowedCalls(scope, agentPrefix), "the agent-prefix scan");
        assertEquals(List.of(new Allowance("scanPipes",
                        "List<String> real = PipeClient.scanPipes(\"bwu_svc_auto_\").stream()")),
                unspentAllowances(scope, agentPrefix), "the service-pipe scan it replaced");
    }

    /**
     * The fourth-round hole, as the reviewer planted it: one of {@code
     * LiveAgent}'s entry points stops listing pipes and, in the same edit, a
     * new direct {@code AgentPin.visiblePipes()} appears. The count is unchanged
     * (two calls, two allowances), so only line pinning can see it: the new call
     * is disallowed, and the dropped line's allowance is reported unspent.
     */
    @Test
    void allowance_swapInOneEdit_isCaughtTwice() {
        String liveAgent = "core/src/test/java/com/botwithus/bot/core/test/LiveAgent.java";
        String intact = wrap("return attachOrFail(System::getProperty, AgentPin::visiblePipes);\n"
                + "        return attachOrSkip(System::getProperty, AgentPin::visiblePipes);");
        String swapped = wrap("return attachOrFail(System::getProperty, AgentPin::visiblePipes);\n"
                + "        return attachOrSkip(System::getProperty, () -> List.of());\n"
                + "        Object planted = AgentPin.visiblePipes();");

        assertEquals(List.of(), disallowedCalls(liveAgent, intact));
        assertEquals(List.of(), unspentAllowances(liveAgent, intact));
        assertEquals(List.of("visiblePipes"), disallowedCalls(liveAgent, swapped), "the planted call");
        assertEquals(List.of(new Allowance("visiblePipes",
                        "return attachOrSkip(System::getProperty, AgentPin::visiblePipes);")),
                unspentAllowances(liveAgent, swapped), "the entry point that stopped listing pipes");
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

    /**
     * The chooser calls in {@code source} that {@link #ALLOWED_CALLS} does not
     * allow at {@code path}, by name. A call is allowed only by an unspent
     * allowance with its name and its exact line, so a repeat, or the same call
     * on another line, is reported.
     */
    static List<String> disallowedCalls(String path, String source) {
        List<Allowance> unspent = new ArrayList<>(ALLOWED_CALLS.getOrDefault(path, List.of()));
        List<String> disallowed = new ArrayList<>();
        for (Call call : chooserCalls(source)) {
            if (!unspent.remove(new Allowance(call.name(), call.line()))) {
                disallowed.add(call.name());
            }
        }
        return List.copyOf(disallowed);
    }

    /** The allowances for {@code path} that no chooser call in {@code source} spends. */
    static List<Allowance> unspentAllowances(String path, String source) {
        List<Allowance> unspent = new ArrayList<>(ALLOWED_CALLS.getOrDefault(path, List.of()));
        for (Call call : chooserCalls(source)) {
            unspent.remove(new Allowance(call.name(), call.line()));
        }
        return List.copyOf(unspent);
    }

    /** Each chooser call in {@code source}: its leading identifier and its line, as the guard sees it. */
    private static List<Call> chooserCalls(String source) {
        String decoded = UNICODE_ESCAPE.matcher(source).replaceAll(LiveAgentIsTheOnlyChooserTest::decodeEscape);
        // Two views with identical layout: matching runs where strings are blanked too, so a
        // string can neither hide nor fake a call; the line an allowance pins comes from the
        // view that keeps strings, so an allowed call's arguments are part of what is pinned.
        String matchable = NOT_CODE.matcher(decoded).replaceAll(match -> blankOut(match, false));
        String pinnable = NOT_CODE.matcher(decoded).replaceAll(match -> blankOut(match, true));
        return CHOOSER.matcher(matchable).results()
                .map(match -> new Call(callName(match), lineAround(pinnable, match.start())))
                .toList();
    }

    private static String lineAround(String code, int at) {
        int from = code.lastIndexOf('\n', at) + 1;
        int to = code.indexOf('\n', at);
        return code.substring(from, to < 0 ? code.length() : to).strip();
    }

    /** {@code scanPipes}, {@code new PipeClient()}, {@code JBotApplication.main}: the match's first word. */
    private static String callName(MatchResult match) {
        Matcher word = IDENTIFIER.matcher(match.group());
        return word.find() ? word.group() : match.group();
    }

    private static String decodeEscape(MatchResult match) {
        return Matcher.quoteReplacement(Character.toString(Integer.parseInt(match.group(1), HEX)));
    }

    /**
     * Blanks a comment or literal without moving anything: every character but a
     * newline becomes a space, so an offset in one view is the same place in the
     * other. Comments always go. A literal keeps its quotes, so the code around it
     * still reads as code ({@code new PipeClient("x")} must not become an empty
     * argument list), and keeps its contents too when {@code keepStrings}.
     */
    private static String blankOut(MatchResult match, boolean keepStrings) {
        String text = match.group();
        if (text.startsWith("/")) {
            return blank(text);
        }
        if (keepStrings) {
            return Matcher.quoteReplacement(text);
        }
        int quote = text.startsWith("\"\"\"") ? TEXT_BLOCK_QUOTES : 1;
        String inner = text.substring(quote, text.length() - quote);
        return Matcher.quoteReplacement(text.substring(0, quote) + blank(inner) + text.substring(text.length() - quote));
    }

    private static String blank(String text) {
        return NOT_NEWLINE.matcher(text).replaceAll(" ");
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
