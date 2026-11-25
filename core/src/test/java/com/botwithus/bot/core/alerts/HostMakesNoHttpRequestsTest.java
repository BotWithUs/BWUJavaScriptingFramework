package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static proof that the host's own code has no HTTP client: every request that
 * leaves the machine for alerts belongs to the launcher, and the host only hands
 * it finished messages over the launcher's pipe.
 *
 * <p>It scans the main Java sources of {@code api}, {@code core} and {@code cli},
 * {@code module-info.java} included, for the JDK's HTTP client and for
 * {@code URLConnection}, and reads the built {@code api} and {@code core} jars
 * for a reference to either. Each scan also requires a file it must have seen,
 * so it cannot pass by reading nothing.</p>
 *
 * <p>One line is allowed, and required: {@link #ALLOWED_REQUIRE} in the
 * {@code cli} module descriptor. It exists only so that user scripts, which
 * resolve against the host's boot layer, can require the JDK HTTP client
 * module. It is matched as a whole trimmed line and must appear exactly once,
 * so a second copy, a {@code requires transitive} or {@code static} variant, or
 * any import or use anywhere still fails.</p>
 */
class HostMakesNoHttpRequestsTest {

    private static final String HOST_JAR = "botwithus.test.hostJar";
    private static final String API_JAR = "botwithus.test.apiJar";
    private static final String REPO_ROOT = "botwithus.test.repoRoot";
    private static final String MAIN_JAVA = "src/main/java";
    private static final List<String> MODULES = List.of("api", "core", "cli");

    /** What a use of an HTTP client looks like in source. */
    private static final List<String> SOURCE_TOKENS = List.of("java.net.http", "HttpClient", "URLConnection");
    /** What a use of one looks like in a compiled class: its internal name. */
    private static final List<String> CLASS_TOKENS = List.of("java/net/http/", "java/net/URLConnection",
            "java/net/HttpURLConnection");

    /** The one descriptor allowed to name the module, relative to the repo root. */
    private static final Path ALLOWED_DESCRIPTOR = Path.of("cli", MAIN_JAVA, "module-info.java");
    /** The one line it may name it in, compared after trimming. */
    private static final String ALLOWED_REQUIRE = "requires java.net.http;";

    /** A source the scan must see, so a scan of nothing fails. */
    private static final String MUST_SEE_SOURCE = "LauncherAlerts.java";
    /** A class each jar scan must see. */
    private static final String MUST_SEE_HOST_CLASS = "com/botwithus/bot/core/launcher/LauncherAlerts.class";
    private static final String MUST_SEE_API_CLASS = "com/botwithus/bot/api/BotScript.class";

    @Test
    void noHostSourceUsesAnHttpClient() throws IOException {
        Path root = Path.of(required(REPO_ROOT));
        List<Path> sources = mainJavaSources(root);
        assertTrue(sources.stream().anyMatch(p -> p.endsWith(MUST_SEE_SOURCE)),
                MUST_SEE_SOURCE + " was not among the scanned sources under " + root + ", so this proves nothing");
        assertTrue(sources.stream().anyMatch(p -> p.endsWith("module-info.java")),
                "no module-info.java was scanned, so a 'requires java.net.http' would go unseen");
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = scannableText(root, source);
            for (String token : SOURCE_TOKENS) {
                if (text.contains(token)) {
                    offenders.add(root.relativize(source) + " uses " + token);
                }
            }
        }
        assertEquals(List.of(), offenders, "the host makes no HTTP requests; the launcher sends alerts");
    }

    @Test
    void theHostJarsReferenceNoHttpClient() throws IOException {
        List<String> offenders = new ArrayList<>();
        offenders.addAll(jarOffenders(Path.of(required(HOST_JAR)), MUST_SEE_HOST_CLASS));
        offenders.addAll(jarOffenders(Path.of(required(API_JAR)), MUST_SEE_API_CLASS));
        assertEquals(List.of(), offenders, "the host makes no HTTP requests; the launcher sends alerts");
    }

    /**
     * The source's text, minus the one allowed line when it is the allowed
     * descriptor. Fails unless that descriptor holds the line exactly once.
     */
    private static String scannableText(Path root, Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        if (!root.relativize(source).equals(ALLOWED_DESCRIPTOR)) {
            return text;
        }
        List<String> lines = text.lines().toList();
        long allowedCount = lines.stream().filter(HostMakesNoHttpRequestsTest::isAllowedRequire).count();
        assertEquals(1L, allowedCount, ALLOWED_DESCRIPTOR + " must say '" + ALLOWED_REQUIRE
                + "' exactly once: it keeps the module in the boot layer for user scripts");
        return lines.stream()
                .filter(line -> !isAllowedRequire(line))
                .collect(Collectors.joining("\n"));
    }

    private static boolean isAllowedRequire(String line) {
        return line.strip().equals(ALLOWED_REQUIRE);
    }

    private static List<String> jarOffenders(Path jarPath, String mustSeeClass) throws IOException {
        List<String> offenders = new ArrayList<>();
        boolean isSeen = false;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                isSeen |= entry.getName().equals(mustSeeClass);
                String text = readLatin1(jar, entry);
                for (String token : CLASS_TOKENS) {
                    if (text.contains(token)) {
                        offenders.add(jarPath.getFileName() + "!" + entry.getName() + " references " + token);
                    }
                }
            }
        }
        assertTrue(isSeen, mustSeeClass + " was not in " + jarPath + ", so this scan proves nothing");
        return offenders;
    }

    private static List<Path> mainJavaSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String module : MODULES) {
            Path mainJava = root.resolve(module).resolve(MAIN_JAVA);
            assertTrue(Files.isDirectory(mainJava), mainJava + " is missing");
            try (Stream<Path> walk = Files.walk(mainJava)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        return sources;
    }

    /** Latin-1 maps every byte to one char, so an ASCII name is found wherever it sits. */
    private static String readLatin1(ZipFile jar, ZipEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, property + " is not set; run this through Gradle's :core:test");
        return value;
    }
}
