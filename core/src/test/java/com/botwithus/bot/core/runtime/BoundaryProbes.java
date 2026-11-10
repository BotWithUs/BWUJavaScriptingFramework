package com.botwithus.bot.core.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The script JARs {@link ScriptModuleBoundaryTest} loads, compiled at test time.
 *
 * <p>Each probe is a {@code BotScript} whose loop does one thing with a type of
 * the host module, {@value #HOST_TYPE}, and the test reads what that loop did.
 * The probes that name the type in their code are compiled against a stand-in
 * of the host module that exports its package to everyone, as the host did
 * before its exports were narrowed, because a script built then is the one the
 * boundary has to hold against. They run against the real module.</p>
 */
final class BoundaryProbes {

    /** Uses only the api; the control that shows a script loads and runs at all. */
    static final String CONTROL = "probe.control";
    /** Requires the host module and names one of its types. */
    static final String REQUIRES_HOST = "probe.requireshost";
    /** Requires only the host front-end, which passes the host module on, and names a host type. */
    static final String REQUIRES_FRONT_END = "probe.requiresfrontend";
    /** Has no module descriptor and names a host type. */
    static final String AUTOMATIC = "probe.automatic";
    /** Names no host type in its code; it looks one up by name when it runs. */
    static final String BY_NAME = "probe.byname";
    /** The host front-end, the one module the host module exports to. */
    static final String FRONT_END = "com.botwithus.bot.cli";
    /** The host type every probe reaches for. */
    static final String HOST_TYPE = "com.botwithus.bot.core.pipe.PipeClient";

    private static final String HOST = HostInternalsCheck.HOST_MODULE;
    private static final String HOST_PACKAGE = "com.botwithus.bot.core.pipe";
    private static final String HOST_FIELD = "NAME_PREFIX";
    private static final String API = "com.botwithus.bot.api";
    private static final String SERVICE = API + ".BotScript";
    private static final String PROBE_CLASS = "Probe";
    private static final String SERVICES_DIR = "META-INF/services";
    private static final String NAMES_TYPE = "String name = " + HOST_TYPE + ".class.getName();";
    private static final String LOOKS_UP_TYPE = "try { Class.forName(\"" + HOST_TYPE + "\").getField(\""
            + HOST_FIELD + "\").get(null); } catch (ReflectiveOperationException e) {"
            + " throw new IllegalStateException(e); }";

    private final Path work;
    private final List<Path> hostDeps;

    /**
     * @param work     scratch space for sources and classes
     * @param hostDeps the host module's runtime dependencies, the api among them
     */
    BoundaryProbes(Path work, List<Path> hostDeps) {
        this.work = work;
        this.hostDeps = List.copyOf(hostDeps);
    }

    /**
     * A module with the front-end's name for the host's module path: it requires
     * the host module transitively and provides a script that names a host type,
     * so it stands in for the front-end in both of the ways the test needs.
     */
    Path frontEnd(Path dir, Path hostJar) throws IOException {
        Path src = probeSource("frontend", "frontend", NAMES_TYPE);
        moduleInfo(src, FRONT_END, "requires transitive " + HOST + "; requires " + API + ";", "frontend");
        return TestModules.jar(dir.resolve("front-end.jar"), compile(src, modulePath(hostJar)));
    }

    /** Every probe, written into {@code scriptsDir}. */
    void writeProbes(Path scriptsDir, Path frontEnd) throws IOException {
        Path exportingHost = exportingHostStandIn();
        named(scriptsDir, CONTROL, "", "", modulePath());
        named(scriptsDir, REQUIRES_HOST, "requires " + HOST + ";", NAMES_TYPE, modulePath(exportingHost));
        named(scriptsDir, REQUIRES_FRONT_END, "requires " + FRONT_END + ";", NAMES_TYPE,
                modulePath(exportingHost, frontEnd));
        named(scriptsDir, BY_NAME, "", LOOKS_UP_TYPE, modulePath());
        automatic(scriptsDir, exportingHost);
    }

    /** A script whose module has the front-end's name; it does nothing else. */
    Path namedLikeTheFrontEnd(Path scriptsDir) throws IOException {
        Path src = probeSource("frontendname", "frontendname", "");
        moduleInfo(src, FRONT_END, "requires " + API + ";", "frontendname");
        return TestModules.jar(scriptsDir.resolve("named-like-front-end.jar"), compile(src, modulePath()));
    }

    /** The host module as it was before its exports were narrowed: the probes' package is exported to all. */
    private Path exportingHostStandIn() throws IOException {
        Path src = work.resolve("exporting-host-src");
        Path pkg = Files.createDirectories(src.resolve(HOST_PACKAGE.replace('.', '/')));
        Files.writeString(src.resolve("module-info.java"), "module " + HOST + " { exports " + HOST_PACKAGE + "; }");
        Files.writeString(pkg.resolve("PipeClient.java"), "package " + HOST_PACKAGE
                + "; public class PipeClient { public static final String " + HOST_FIELD + " = \"\"; }");
        return TestModules.jar(work.resolve("exporting-host.jar"), compile(src, List.of()));
    }

    private void named(Path scriptsDir, String module, String requires, String body, List<String> options)
            throws IOException {
        String pkg = module.replace(".", "");
        Path src = probeSource(pkg, pkg, body);
        moduleInfo(src, module, requires + " requires " + API + ";", pkg);
        TestModules.jar(scriptsDir.resolve(pkg + ".jar"), compile(src, options));
    }

    private void automatic(Path scriptsDir, Path exportingHost) throws IOException {
        String pkg = AUTOMATIC.replace(".", "");
        Path src = probeSource(pkg, pkg, NAMES_TYPE);
        List<Path> classPath = new ArrayList<>(hostDeps);
        classPath.add(exportingHost);
        Path classes = compile(src, List.of("--class-path", joined(classPath)));
        Path services = Files.createDirectories(classes.resolve(SERVICES_DIR));
        Files.writeString(services.resolve(SERVICE), pkg + "." + PROBE_CLASS);
        // The module name of a JAR with no descriptor comes from its file name.
        TestModules.jar(scriptsDir.resolve(AUTOMATIC.replace('.', '-') + ".jar"), classes);
    }

    private Path probeSource(String dirName, String pkg, String body) throws IOException {
        Path src = work.resolve(dirName + "-src");
        Path dir = Files.createDirectories(src.resolve(pkg));
        Files.writeString(dir.resolve(PROBE_CLASS + ".java"), "package " + pkg + "; public class "
                + PROBE_CLASS + " implements " + SERVICE + " {"
                + " public void onStart(" + API + ".ScriptContext ctx) { }"
                + " public int onLoop() { " + body + " return 0; }"
                + " public void onStop() { } }");
        return src;
    }

    private static void moduleInfo(Path src, String module, String directives, String pkg) throws IOException {
        Files.writeString(src.resolve("module-info.java"), "module " + module + " { " + directives
                + " provides " + SERVICE + " with " + pkg + "." + PROBE_CLASS + "; }");
    }

    private List<String> modulePath(Path... first) {
        List<Path> path = new ArrayList<>(List.of(first));
        path.addAll(hostDeps);
        return List.of("--module-path", joined(path));
    }

    private static String joined(List<Path> path) {
        return String.join(File.pathSeparator, path.stream().map(Path::toString).toList());
    }

    private static Path compile(Path src, List<String> options) throws IOException {
        Path classes = Files.createDirectories(src.resolveSibling(src.getFileName() + "-classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(options);
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(file -> file.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int status;
        try (PrintStream out = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            status = javac.run(null, out, out, args.toArray(String[]::new));
        }
        assertEquals(0, status, () -> "the probe must compile: " + diagnostics.toString(StandardCharsets.UTF_8));
        return classes;
    }
}
