package com.botwithus.bot.core.resolver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MavenCoordTest {

    @Test
    void parseTwoParts_yieldsVersionlessCoord() {
        Optional<MavenCoord> parsed = MavenCoord.parse("com.example:my-script");
        assertTrue(parsed.isPresent());
        assertEquals("com.example", parsed.get().groupId());
        assertEquals("my-script", parsed.get().artifactId());
        assertTrue(parsed.get().version().isEmpty());
    }

    @Test
    void parseThreeParts_yieldsVersionedCoord() {
        Optional<MavenCoord> parsed = MavenCoord.parse("com.example:my-script:1.2.3");
        assertTrue(parsed.isPresent());
        assertEquals("1.2.3", parsed.get().version().orElseThrow());
    }

    @Test
    void parseEmpty_yieldsEmpty() {
        assertTrue(MavenCoord.parse("").isEmpty());
        assertTrue(MavenCoord.parse(null).isEmpty());
        assertTrue(MavenCoord.parse("just-one-part").isEmpty());
        assertTrue(MavenCoord.parse("a:b:c:d").isEmpty());
        assertTrue(MavenCoord.parse("a::1.0").isEmpty());
        assertTrue(MavenCoord.parse(":a:1.0").isEmpty());
    }

    @Test
    void groupPath_replacesDotsWithSlashes() {
        MavenCoord c = MavenCoord.of("org.apache.commons", "commons-lang3");
        assertEquals("org/apache/commons/commons-lang3", c.groupPath());
    }

    @Test
    void versionPath_appendsVersion() {
        MavenCoord c = MavenCoord.of("com.example", "art");
        assertEquals("com/example/art/2.0", c.versionPath("2.0"));
    }

    @Test
    void jarFileName_combinesArtifactAndVersion() {
        MavenCoord c = MavenCoord.of("com.example", "art");
        assertEquals("art-1.0.jar", c.jarFileName("1.0"));
    }

    @Test
    void toString_includesVersionWhenPresent() {
        assertEquals("com.example:art:1.0",
                MavenCoord.of("com.example", "art", "1.0").toString());
        assertEquals("com.example:art",
                MavenCoord.of("com.example", "art").toString());
    }

    @Test
    void blankFieldsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("", "art", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("g", "", "1.0"));
    }

    @Test
    void traversalVersionRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("com.example", "art", "../../../../evil"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("com.example", "art", ".."));
    }

    @Test
    void separatorsInGroupOrArtifactRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("com/example", "art", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("com.example", "../evil", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> MavenCoord.of("com.example", "evil\\path", "1.0"));
    }

    @Test
    void parseTraversalSpecYieldsEmpty() {
        assertTrue(MavenCoord.parse("com.example:art:../../../../evil").isEmpty());
        assertTrue(MavenCoord.parse("com.example:../evil:1.0").isEmpty());
    }

    @Test
    void isValidTokenTruthTable() {
        assertTrue(MavenCoord.isValidToken("1.0.0"));
        assertTrue(MavenCoord.isValidToken("com.example"));
        assertTrue(MavenCoord.isValidToken("my-script"));
        assertTrue(MavenCoord.isValidToken("1.0.0-SNAPSHOT"));
        assertTrue(MavenCoord.isValidToken("2.0.0+build7"));

        assertFalse(MavenCoord.isValidToken(null));
        assertFalse(MavenCoord.isValidToken(""));
        assertFalse(MavenCoord.isValidToken("   "));
        assertFalse(MavenCoord.isValidToken(".."));
        assertFalse(MavenCoord.isValidToken("a..b"));
        assertFalse(MavenCoord.isValidToken("a/b"));
        assertFalse(MavenCoord.isValidToken("a\\b"));
        assertFalse(MavenCoord.isValidToken("a b"));
    }
}
