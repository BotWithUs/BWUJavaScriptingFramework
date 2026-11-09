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

    private static final String HOST_PACKAGE = "hoststandin";

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
        return moduleInfoOnly(dir.resolve("reacher.jar"), work.resolve("reacher-src"),
                "module " + REACHER + " { requires " + HostInternalsCheck.HOST_MODULE + "; }", List.of(host));
    }

    /** {@value #BYSTANDER}, which requires only a platform module. */
    static Path bystander(Path dir, Path work) throws IOException {
        return moduleInfoOnly(dir.resolve("bystander.jar"), work.resolve("bystander-src"),
                "module " + BYSTANDER + " { requires java.sql; }", List.of());
    }

    private static Path moduleInfoOnly(Path jar, Path src, String moduleInfo, List<Path> modulePath)
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

    private static Path jar(Path jar, Path classes) throws IOException {
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
