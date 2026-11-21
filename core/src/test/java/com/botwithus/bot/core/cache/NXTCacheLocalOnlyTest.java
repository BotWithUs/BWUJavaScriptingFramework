package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static proof that the host cannot ask {@code NXTCache.dll} for the network.
 *
 * <p>Every native entry point is bound by name through Panama's
 * {@code SymbolLookup.find(String)}, so a binding to one must carry that name
 * as a string constant in the compiled class. This test reads the host's
 * built jar and fails if any of the library's network entry points is named in
 * it. It also requires the local open to be named, which proves the scan can
 * see a binding at all rather than passing because it read nothing.</p>
 *
 * <p>It scans the main Java sources of every module too, so a binding added
 * outside {@code core} is caught before it is ever built into a jar.</p>
 *
 * <p>It never loads {@code NXTCache.dll}, so it runs everywhere, CI included.</p>
 */
class NXTCacheLocalOnlyTest {

    private static final String HOST_JAR = "botwithus.test.hostJar";
    private static final String REPO_ROOT = "botwithus.test.repoRoot";
    private static final String MAIN_JAVA = "src/main/java";

    /**
     * The library's entry points that open a JS5 connection. A prefix covers
     * its variants ({@code _beta}, {@code _with_config}).
     */
    private static final List<String> NETWORK_ENTRY_POINTS = List.of(
            "nxt_cache_open_live",
            "nxt_cache_enable_live_fallback",
            "nxt_fetch_master_crcs");

    /** The one open the host is allowed; its presence proves the scan sees bindings. */
    private static final String LOCAL_OPEN = "nxt_cache_open_local";

    @Test
    void theHostJarBindsNoNetworkEntryPoint() throws IOException {
        Path hostJar = Path.of(required(HOST_JAR));
        List<String> offenders = new ArrayList<>();
        boolean isLocalOpenBound = false;
        try (ZipFile jar = new ZipFile(hostJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String text = readLatin1(jar, entry);
                isLocalOpenBound |= text.contains(LOCAL_OPEN);
                for (String name : NETWORK_ENTRY_POINTS) {
                    if (text.contains(name)) {
                        offenders.add(entry.getName() + " names " + name);
                    }
                }
            }
        }
        assertTrue(isLocalOpenBound, LOCAL_OPEN + " was not found in " + hostJar
                + ", so this scan cannot see bindings and proves nothing");
        assertEquals(List.of(), offenders,
                "the host must never bind a network entry point of NXTCache.dll");
    }

    @Test
    void noModuleSourceNamesANetworkEntryPoint() throws IOException {
        Path root = Path.of(required(REPO_ROOT));
        List<Path> sources = mainJavaSources(root);
        assertTrue(sources.stream().anyMatch(p -> p.endsWith("NXTCache.java")),
                "NXTCache.java was not among the scanned sources under " + root
                        + ", so this scan proves nothing");
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String name : NETWORK_ENTRY_POINTS) {
                if (text.contains("\"" + name)) {
                    offenders.add(root.relativize(source) + " names " + name);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "no module may bind a network entry point of NXTCache.dll");
    }

    /** Every {@code <module>/src/main/java/**.java} directly under {@code root}. */
    private static List<Path> mainJavaSources(Path root) throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path mainJava = module.resolve(MAIN_JAVA);
                if (Files.isDirectory(mainJava)) {
                    try (Stream<Path> walk = Files.walk(mainJava)) {
                        walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
                    }
                }
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
        assertNotNull(value, property + " is set by the Gradle test task; run this test through it");
        return value;
    }
}
