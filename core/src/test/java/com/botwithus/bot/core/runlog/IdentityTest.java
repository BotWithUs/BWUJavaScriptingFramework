package com.botwithus.bot.core.runlog;

import com.botwithus.bot.core.shm.Layout;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The header values the host derives itself: host identity and script identity. */
class IdentityTest {

    @Test
    void hostVersion_isTheModuleVersion_ofANamedModule() {
        // java.base is a named module with a version, the shape the host's own
        // modules have once Gradle stamps javaModuleVersion.
        HostIdentity host = HostIdentity.current(Object.class);
        String jdk = Runtime.version().version().stream().map(String::valueOf)
                .collect(Collectors.joining("."));
        assertAll(
                () -> assertTrue(host.hostVersion().startsWith(jdk), host.hostVersion()),
                () -> assertEquals(Layout.PROTOCOL_VERSION, host.protocolVersion()),
                () -> assertEquals("Java " + jdk, host.runtime()),
                () -> assertTrue(host.os().startsWith(System.getProperty("os.name"))));
    }

    @Test
    void hostVersion_isUnknown_whenNothingCarriesOne() {
        assertEquals(RunLogHeader.UNKNOWN, HostIdentity.current(IdentityTest.class).hostVersion());
    }

    @Test
    void aClassFromAJar_isLocal_andTheJarIsHashed() throws Exception {
        // org.junit.jupiter.api.Test is loaded from the junit-jupiter-api jar on
        // the test classpath: a real jar standing in for a script jar.
        Path jar = Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ScriptIdentity id = ScriptIdentity.of(Test.class);
        assertAll(
                () -> assertTrue(jar.toString().endsWith(".jar"), jar.toString()),
                () -> assertEquals(RunLogHeader.SOURCE_LOCAL, id.source()),
                () -> assertEquals(sha256(jar), id.sha256()));
    }

    @Test
    void aClassFromADirectory_isLocal_withNoHash() {
        ScriptIdentity id = ScriptIdentity.of(IdentityTest.class);
        assertAll(
                () -> assertEquals(RunLogHeader.SOURCE_LOCAL, id.source()),
                () -> assertEquals(null, id.sha256()));
    }

    @Test
    void aClassWithNoCodeSource_isSdn() {
        // A bootstrap class has no code source, as a class defined in memory by
        // the SDN loader has none.
        ScriptIdentity id = ScriptIdentity.of(String.class);
        assertAll(
                () -> assertEquals(RunLogHeader.SOURCE_SDN, id.source()),
                () -> assertEquals(null, id.sha256()),
                () -> assertEquals("String", id.name()));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            digest.update(in.readAllBytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
