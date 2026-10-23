package com.botwithus.bot.core.sdn;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdnUpdateStatusTest {

    private static final int OLD = 6;
    private static final int NEW = 7;

    @Test
    void of_installedOlderThanCurrent_isAvailableFromTo() {
        assertEquals(new SdnUpdateStatus.Available(OLD, NEW), SdnUpdateStatus.of(OLD, NEW));
    }

    @Test
    void of_installedEqualsCurrent_isUpToDate() {
        assertEquals(new SdnUpdateStatus.UpToDate(NEW), SdnUpdateStatus.of(NEW, NEW));
    }

    @Test
    void of_installedNewerThanCurrent_isUpToDateNotADowngrade() {
        assertEquals(new SdnUpdateStatus.UpToDate(NEW), SdnUpdateStatus.of(NEW, OLD));
    }

    @Test
    void of_installedBuildUnknown_isUnknown() {
        assertEquals(new SdnUpdateStatus.Unknown(), SdnUpdateStatus.of(null, NEW));
    }

    @Test
    void of_currentBuildUnknown_isUnknown() {
        assertEquals(new SdnUpdateStatus.Unknown(), SdnUpdateStatus.of(OLD, null));
    }

    @Test
    void of_bothUnknown_isUnknown() {
        assertEquals(new SdnUpdateStatus.Unknown(), SdnUpdateStatus.of(null, null));
    }

    @Test
    void updateAgainst_comparesTheRecordWithTheEntrysCurrentBuild() {
        InstalledSdnScript installed = new InstalledSdnScript("7", OLD, Instant.EPOCH);
        SdnCatalogueEntry entry = new SdnCatalogueEntry("7", "A", "ada", "bob", "1", "2", "", "", "a.B",
                false, true, true, false, null, null, NEW);

        assertEquals(new SdnUpdateStatus.Available(OLD, NEW), installed.updateAgainst(entry));
    }
}
