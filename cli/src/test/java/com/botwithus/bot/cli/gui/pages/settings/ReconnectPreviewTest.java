package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.ReconnectPolicySettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.rpc.ReconnectPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconnectPreviewTest {

    private static final long FIRST_MS = 500L;
    private static final long LONGEST_MS = 15_000L;
    private static final double DOUBLING = 2.0;

    @TempDir
    Path dir;

    @Test
    void theDefaultPolicyShowsTenTriesCappedAtTheLongestWait() {
        ReconnectPreview preview = ReconnectPreview.of(ReconnectPolicy.DEFAULT);

        assertEquals(List.of(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L, 15_000L, 15_000L),
                preview.waitsMs());
        assertEquals("Waits 500 ms, 1 s, 2 s, 4 s… then every 15 s, until the client is back. "
                + "The first 10 tries take 1 min 30 s.", preview.summary());
        assertEquals(LONGEST_MS, preview.longestMs());
    }

    @Test
    void aCapShowsOnlyTheTriesThatWillBeMade() {
        ReconnectPreview preview = ReconnectPreview.of(new ReconnectPolicy(3, FIRST_MS, DOUBLING, LONGEST_MS));

        assertEquals(List.of(500L, 1_000L, 2_000L), preview.waitsMs());
        assertEquals("Waits 500 ms, 1 s, 2 s, up to 3 tries. All 3 tries take 3.5 s.", preview.summary());
    }

    @Test
    void aSlowBackOffThatNeverReachesTheCapSaysSo() {
        ReconnectPreview preview = ReconnectPreview.of(new ReconnectPolicy(0, 1_000L, 1.1, 60_000L));

        assertEquals("Waits 1 s, 1.1 s, 1.2 s, 1.3 s… growing to at most 1 min, until the client is back. "
                + "The first 10 tries take 15.9 s.", preview.summary());
    }

    @Test
    void thePreviewFollowsTheReconnectSettings() {
        try (HostSettings settings = HostSettings.open(dir)) {
            settings.set(SettingKeys.RECONNECT_INITIAL_DELAY_MS, 1_000L);
            settings.set(SettingKeys.RECONNECT_BACKOFF, 3.0);
            settings.set(SettingKeys.RECONNECT_MAX_DELAY_MS, 10_000L);
            settings.set(SettingKeys.RECONNECT_MAX_ATTEMPTS, 4L);

            ReconnectPreview preview = ReconnectPreview.of(ReconnectPolicySettings.read(settings));

            assertEquals(List.of(1_000L, 3_000L, 9_000L, 10_000L), preview.waitsMs());
        }
    }
}
