package com.botwithus.bot.core.alerts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertDeliveryTest {

    private static final Instant AT = Instant.parse("2026-11-21T09:30:00Z");
    private static final AlertMessage CRASH = new AlertMessage("Script crashed", "Fishing crashed on Hollowmere", true);
    /** The launcher's limit on a dedupe key. */
    private static final int MAX_KEY_CHARS = 128;

    @Test
    void composingTheSameMessageAgain_givesTheSameKey() {
        AlertDelivery first = AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.NTFY), AT);
        AlertDelivery again = AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.NTFY), AT);

        assertEquals(first.dedupeKey().orElseThrow(), again.dedupeKey().orElseThrow());
    }

    @Test
    void anotherService_anotherTime_orOtherWords_giveAnotherKey() {
        String key = AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.NTFY), AT).dedupeKey()
                .orElseThrow();

        assertNotEquals(key, AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.SLACK), AT).dedupeKey()
                .orElseThrow());
        assertNotEquals(key, AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.NTFY),
                AT.plusMillis(1)).dedupeKey().orElseThrow());
        assertNotEquals(key, AlertDelivery.compose("scriptCrash",
                new AlertMessage("Script crashed", "Mining crashed on Hollowmere", true),
                Set.of(AlertService.NTFY), AT).dedupeKey().orElseThrow());
    }

    @Test
    void theKey_namesItsKind_andFitsTheLaunchersLimit() {
        String key = AlertDelivery.compose(AlertDelivery.KIND_BURST, CRASH, Set.of(AlertService.values()), AT)
                .dedupeKey().orElseThrow();

        assertTrue(key.startsWith(AlertDelivery.KIND_BURST + "-"), key);
        assertTrue(key.length() <= MAX_KEY_CHARS, key);
        assertTrue(key.matches("[A-Za-z0-9_.-]+"), key);
    }

    @Test
    void theOrderServicesAreGivenIn_doesNotChangeTheKey() {
        assertEquals(
                AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.DISCORD, AlertService.NTFY), AT)
                        .dedupeKey().orElseThrow(),
                AlertDelivery.compose("scriptCrash", CRASH, Set.of(AlertService.NTFY, AlertService.DISCORD), AT)
                        .dedupeKey().orElseThrow());
    }

    @Test
    void aDeliveryToNoService_isRefused() {
        assertThrows(IllegalArgumentException.class, () -> AlertDelivery.compose("scriptCrash", CRASH, Set.of(), AT));
    }
}
