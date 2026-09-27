package com.botwithus.bot.cli;

import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.config.ScriptProfileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link AutoStartManager} against a real {@link HostSettings};
 * only the OS pipe listing is replaced, by one that records the prefix it was asked for.
 */
class AutoStartManagerTest {

    private static final long SCAN_WAIT_SECONDS = 5L;
    private static final long FASTEST_SCAN_MS = 500L;

    @TempDir
    Path dir;

    private final LinkedBlockingQueue<String> scannedPrefixes = new LinkedBlockingQueue<>();
    private HostSettings settings;
    private AutoStartManager manager;

    @BeforeEach
    void setUp() {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        CliContext ctx = new CliContext(new LogBuffer(), new LogCapture(new LogBuffer(), sink, sink));
        settings = HostSettings.open(dir);
        settings.set(SettingKeys.SCAN_INTERVAL_MS, FASTEST_SCAN_MS);
        manager = new AutoStartManager(ctx, new ScriptProfileStore(dir), settings,
                prefix -> {
                    scannedPrefixes.add(prefix);
                    return List.of();
                },
                Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        settings.close();
    }

    private String nextScan() throws InterruptedException {
        return scannedPrefixes.poll(SCAN_WAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void start_withAutoConnectOff_doesNotScan() throws InterruptedException {
        settings.set(SettingKeys.AUTO_CONNECT, false);

        manager.start();

        assertFalse(manager.isScanning());
        assertNull(scannedPrefixes.poll(FASTEST_SCAN_MS * 2, TimeUnit.MILLISECONDS));
    }

    @Test
    void togglingAutoConnect_startsAndStopsTheScanner() throws InterruptedException {
        settings.set(SettingKeys.AUTO_CONNECT, false);
        manager.start();

        settings.set(SettingKeys.AUTO_CONNECT, true);
        assertTrue(manager.isScanning(), "switching auto-connect on must start the scanner");
        assertEquals("BotWithUs", nextScan());

        settings.set(SettingKeys.AUTO_CONNECT, false);
        assertFalse(manager.isScanning(), "switching auto-connect off must stop the scanner");
        scannedPrefixes.poll(FASTEST_SCAN_MS * 2, TimeUnit.MILLISECONDS); // a scan already under way
        scannedPrefixes.clear();
        assertNull(scannedPrefixes.poll(FASTEST_SCAN_MS * 3, TimeUnit.MILLISECONDS),
                "no scans once auto-connect is off");

        settings.set(SettingKeys.AUTO_CONNECT, true);
        assertTrue(manager.isScanning(), "and on again");
        assertEquals("BotWithUs", nextScan());
    }

    @Test
    void pipePrefix_isReadLiveOnEveryScan() throws InterruptedException {
        manager.start();
        assertEquals("BotWithUs", nextScan());

        settings.set(SettingKeys.PIPE_PREFIX, "OtherPrefix");

        // A scan already under way may still use the old prefix; the one after it must not.
        String first = nextScan();
        String scanned = "OtherPrefix".equals(first) ? first : nextScan();
        assertEquals("OtherPrefix", scanned);
    }

    @Test
    void stop_stopsFollowingTheSetting() throws InterruptedException {
        settings.set(SettingKeys.AUTO_CONNECT, false);
        manager.start();
        manager.stop();

        settings.set(SettingKeys.AUTO_CONNECT, true);

        assertFalse(manager.isScanning());
        assertNull(scannedPrefixes.poll(FASTEST_SCAN_MS * 2, TimeUnit.MILLISECONDS));
    }
}
