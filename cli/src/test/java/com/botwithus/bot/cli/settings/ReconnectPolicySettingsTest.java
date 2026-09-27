package com.botwithus.bot.cli.settings;

import com.botwithus.bot.core.rpc.ReconnectPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The reconnect policy comes from the settings as they are when it is read. */
class ReconnectPolicySettingsTest {

    private static final long MINUTE_MS = 60_000L;
    private static final long SECOND_MS = 1_000L;
    private static final long BUDGET = 7L;
    private static final double BACKOFF = 1.5;

    @TempDir
    Path dir;

    private HostSettings settings;

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
    }

    /** Closed so no debounced write races the temp folder's deletion. */
    @AfterEach
    void close() {
        settings.close();
    }

    @Test
    void theDefaultsDescribeAnUnlimitedPolicy() {
        ReconnectPolicy policy = ReconnectPolicySettings.read(settings);

        assertEquals(OptionalInt.empty(), policy.attemptLimit(), "0 attempts means no cap");
        assertEquals(ReconnectPolicy.DEFAULT, policy);
    }

    @Test
    void eachSettingReachesThePolicy() {
        settings.set(SettingKeys.RECONNECT_MAX_ATTEMPTS, BUDGET);
        settings.set(SettingKeys.RECONNECT_INITIAL_DELAY_MS, SECOND_MS);
        settings.set(SettingKeys.RECONNECT_BACKOFF, BACKOFF);
        settings.set(SettingKeys.RECONNECT_MAX_DELAY_MS, MINUTE_MS);

        assertEquals(new ReconnectPolicy((int) BUDGET, SECOND_MS, BACKOFF, MINUTE_MS),
                ReconnectPolicySettings.read(settings));
    }

    /**
     * Each setting is valid on its own, so the store accepts both; together they
     * describe a policy the policy type refuses. Reading must not throw on them,
     * because it runs on the thread that is trying to reconnect.
     */
    @Test
    void aLongestWaitShorterThanTheFirstWaitIsRaisedToIt() {
        settings.set(SettingKeys.RECONNECT_INITIAL_DELAY_MS, MINUTE_MS);
        settings.set(SettingKeys.RECONNECT_MAX_DELAY_MS, SECOND_MS);

        ReconnectPolicy policy = ReconnectPolicySettings.read(settings);

        assertEquals(MINUTE_MS, policy.initialDelayMs());
        assertEquals(MINUTE_MS, policy.maxDelayMs());
    }

    @Test
    void aChangeIsSeenByTheNextRead() {
        ReconnectPolicy before = ReconnectPolicySettings.read(settings);

        settings.set(SettingKeys.RECONNECT_MAX_ATTEMPTS, BUDGET);

        assertEquals(OptionalInt.empty(), before.attemptLimit());
        assertEquals(OptionalInt.of((int) BUDGET), ReconnectPolicySettings.read(settings).attemptLimit());
    }
}
