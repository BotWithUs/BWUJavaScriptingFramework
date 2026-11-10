package com.botwithus.bot.core.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real modular JARs for the host-internals tests, compiled at test time.
 *
 * <p>The test JVM runs {@code core} on the class path, so the boot layer has no
 * {@value HostInternalsCheck#HOST_MODULE} for a script layer to resolve
 * against. {@link #hostStandIn} builds one under that name, and a script
 * directory holding it lets a reaching module resolve: the loaders hand every
 * staged JAR to the finder, so the stand-in joins the reaching module's layer
 * exactly as the boot layer's module would be read in production.</p>
 */
final class TestModules {

    /** A module that reaches into the host: it names the host module in {@code requires}. */
    static final String REACHER = "probe.reacher";
    /** A module that depends on nothing of ours. */
    static final String BYSTANDER = "probe.bystander";

    /** A module that reaches into the host and declares a script provider. */
    static final String PROVIDER = "probe.provider";

    /** A module that reaches into the host and resolves, but whose layer cannot be defined. */
    static final String SPLIT = "probe.split";

    private static final String HOST_PACKAGE = "hoststandin";
    private static final String SPLIT_PACKAGE = "dup";
    private static final String API_PACKAGE = "com.botwithus.bot.api";

    private TestModules() {}

    /** A module named {@value HostInternalsCheck#HOST_MODULE} that exports one package. */
    static Path hostStandIn(Path dir, Path work) throws IOException {
        Path src = Files.createDirectories(work.resolve("host-src").resolve(HOST_PACKAGE));
        Files.writeString(src.resolveSibling("module-info.java"),
                "module " + HostInternalsCheck.HOST_MODULE + " { exports " + HOST_PACKAGE + "; }");
        Files.writeString(src.resolve("Marker.java"),
                "package " + HOST_PACKAGE + "; public class Marker {}");
        return jar(dir.resolve("host-stand-in.jar"), compile(src.getParent(), List.of()));
    }

    /** {@value #REACHER}, compiled against {@code host}. */
    static Path reacher(Path dir, Path work, Path host) throws IOException {
        return buildModule(dir.resolve("reacher.jar"), work.resolve("reacher-src"),
                "module " + REACHER + " { requires " + HostInternalsCheck.HOST_MODULE + "; }", List.of(host));
    }

    /** A module named like the api, holding a {@code BotScript} that is not the host's. */
    static Path apiStandIn(Path dir, Path work) throws IOException {
        Path src = Files.createDirectories(work.resolve("api-src").resolve(API_PACKAGE.replace('.', '/')));
        Files.writeString(work.resolve("api-src").resolve("module-info.java"),
                "module " + API_PACKAGE + " { exports " + API_PACKAGE + "; }");
        Files.writeString(src.resolve("BotScript.java"), "package " + API_PACKAGE + "; public interface BotScript {}");
        return jar(dir.resolve("api-stand-in.jar"), compile(work.resolve("api-src"), List.of()));
    }

    /** {@value #PROVIDER}: reaches into the host and declares a {@code BotScript} provider. */
    static Path provider(Path dir, Path work, Path host, Path api) throws IOException {
        Path root = work.resolve("provider-src");
        Path src = Files.createDirectories(root.resolve("probeprovider"));
        Files.writeString(root.resolve("module-info.java"), "module " + PROVIDER + " { requires "
                + HostInternalsCheck.HOST_MODULE + "; requires " + API_PACKAGE + "; provides "
                + API_PACKAGE + ".BotScript with probeprovider.Impl; }");
        Files.writeString(src.resolve("Impl.java"),
                "package probeprovider; public class Impl implements " + API_PACKAGE + ".BotScript {}");
        return jar(dir.resolve("provider.jar"), compile(root, List.of(host, api)));
    }

    /** {@value #BYSTANDER}, which requires only a platform module. */
    static Path bystander(Path dir, Path work) throws IOException {
        return buildModule(dir.resolve("bystander.jar"), work.resolve("bystander-src"),
                "module " + BYSTANDER + " { requires java.sql; }", List.of());
    }

    /**
     * {@value #SPLIT}, which reaches into the host and resolves, but whose layer
     * cannot be defined: it and a module it reaches only indirectly both contain
     * package {@code dup}, and one class loader cannot define a package twice.
     * The other two JARs are written beside it.
     */
    static Path splitPackage(Path dir, Path work, Path host) throws IOException {
        Path other = withClass(dir.resolve("other.jar"), work.resolve("other-src"),
                "module probe.other { }", "Other", List.of());
        Path middle = buildModule(dir.resolve("middle.jar"), work.resolve("middle-src"),
                "module probe.middle { requires probe.other; }", List.of(other));
        return withClass(dir.resolve("split.jar"), work.resolve("split-src"),
                "module " + SPLIT + " { requires " + HostInternalsCheck.HOST_MODULE + "; requires probe.middle; }",
                "Split", List.of(host, middle, other));
    }

    private static Path withClass(Path jar, Path src, String moduleInfo, String className, List<Path> modulePath)
            throws IOException {
        Path pkg = Files.createDirectories(src.resolve(SPLIT_PACKAGE));
        Files.writeString(pkg.resolve(className + ".java"),
                "package " + SPLIT_PACKAGE + "; public class " + className + " {}");
        return buildModule(jar, src, moduleInfo, modulePath);
    }

    private static Path buildModule(Path jar, Path src, String moduleInfo, List<Path> modulePath)
            throws IOException {
        Files.createDirectories(src);
        Files.writeString(src.resolve("module-info.java"), moduleInfo);
        return jar(jar, compile(src, modulePath));
    }

    private static Path compile(Path src, List<Path> modulePath) throws IOException {
        Path classes = Files.createDirectories(src.resolveSibling(src.getFileName() + "-classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        if (!modulePath.isEmpty()) {
            args.add("--module-path");
            args.add(String.join(File.pathSeparator, modulePath.stream().map(Path::toString).toList()));
        }
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(file -> file.toString().endsWith(".java")).map(Path::toString).forEach(args::add);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the probe must compile");
        return classes;
    }

    static Path jar(Path jar, Path classes) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
