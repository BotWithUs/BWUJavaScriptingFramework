package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.ResolveOutcome;
import com.botwithus.bot.core.resolver.metadata.ChecksumDigest;
import com.botwithus.bot.core.resolver.pgp.PgpVerifier;
import com.botwithus.bot.core.resolver.transport.HttpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test that downloads {@code org.slf4j:slf4j-api} from Maven
 * Central and verifies its checksum. Disabled by default; opt in with
 * {@code -Dbotwithus.smoke.network=true}.
 */
class MavenCentralSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.network", matches = "true")
    void resolvesSlf4jApiFromCentral() throws Exception {
        Repository central = Repository.mavenRelease(
                "central",
                URI.create("https://repo1.maven.org/maven2/"),
                false);

        Resolver resolver = Resolver.withDiscoveredDrivers(
                List.of(central),
                new HttpTransport(),
                PgpVerifier.ALWAYS_REJECT,
                tempDir.resolve("staging"));

        ResolveOutcome outcome = resolver.resolve(MavenCoord.of("org.slf4j", "slf4j-api", "2.0.16"));
        ResolveOutcome.Resolved resolved = assertInstanceOf(ResolveOutcome.Resolved.class, outcome,
                "Maven Central must resolve slf4j-api:2.0.16");
        assertTrue(Files.exists(resolved.artifact().jar()), "JAR must exist locally");
        long jarSize = Files.size(resolved.artifact().jar());
        assertTrue(jarSize > 0, "JAR must be non-empty");

        ChecksumDigest computed = ChecksumDigest.of(resolved.artifact().jar());
        assertArrayEquals(resolved.artifact().sha256(), computed.sha256(),
                "checksum must match the one verified by the resolver");
    }
}
