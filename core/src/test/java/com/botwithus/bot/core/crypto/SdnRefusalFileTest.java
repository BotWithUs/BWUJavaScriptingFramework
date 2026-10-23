package com.botwithus.bot.core.crypto;

import com.botwithus.bot.core.sdn.SdnRendezvous;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The file route hears a courier's "no" at once, and reads its reason. */
class SdnRefusalFileTest {

    private static final Duration LONG_WAIT = Duration.ofSeconds(20);
    private static final Duration SHORT_WAIT = Duration.ofMillis(200);

    @TempDir
    Path dir;

    /** Fails if the error file is ignored: the wait would run to its whole timeout. */
    @Test
    void anErrorFile_endsTheWaitAtOnce() throws Exception {
        Path bundle = dir.resolve("1.sdn");
        Path error = dir.resolve("1.sdnerr");
        Files.writeString(error, "{\"reason\":\"host_not_on_pipe\"}");
        Instant start = Instant.now();

        SdnRendezvous.Arrival arrival = SdnRendezvous.awaitEither(bundle, error, LONG_WAIT);

        assertArrayEquals("{\"reason\":\"host_not_on_pipe\"}".getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(SdnRendezvous.Arrival.Refusal.class, arrival).bytes());
        assertTrue(Duration.between(start, Instant.now()).compareTo(LONG_WAIT) < 0);
    }

    @Test
    void theBundle_winsWhenItIsThere() throws Exception {
        Path bundle = dir.resolve("1.sdn");
        Files.writeString(bundle, "bundle");

        assertInstanceOf(SdnRendezvous.Arrival.Wanted.class,
                SdnRendezvous.awaitEither(bundle, dir.resolve("1.sdnerr"), SHORT_WAIT));
    }

    @Test
    void neither_isNothing() throws Exception {
        assertInstanceOf(SdnRendezvous.Arrival.Nothing.class,
                SdnRendezvous.awaitEither(dir.resolve("1.sdn"), dir.resolve("1.sdnerr"), SHORT_WAIT));
    }

    @Test
    void theReason_isReadFromTheErrorFile() {
        DefinedBundle refused = SdnDiskBundleSource.refusalOf(
                "{\"reason\":\"host_not_on_pipe\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(Optional.of("host_not_on_pipe"), refused.refusal());
        assertTrue(refused.jars().isEmpty());
    }

    /** An unreadable error file is still a refusal, with no reason, so the generic text shows. */
    @Test
    void anUnreadableErrorFile_isARefusalWithNoReason() {
        assertEquals(Optional.of(""), SdnDiskBundleSource.refusalOf(
                "not json".getBytes(StandardCharsets.UTF_8)).refusal());
    }
}
