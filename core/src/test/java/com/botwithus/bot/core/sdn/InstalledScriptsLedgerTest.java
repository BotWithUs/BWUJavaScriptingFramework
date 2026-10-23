package com.botwithus.bot.core.sdn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledScriptsLedgerTest {

    private static final Instant FIRST = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-09-26T12:30:00Z");
    private static final int OLD_BUILD = 6;
    private static final int NEW_BUILD = 7;
    private static final String WOODCUTTER = "com.example.Woodcutter";
    private static final String FLETCHER = "com.example.Fletcher";

    @TempDir
    Path home;

    private InstalledScriptsLedger ledgerAt(Instant now) {
        return new InstalledScriptsLedger(home, InstantSource.fixed(now));
    }

    private static SdnCatalogueEntry entry(String id, Integer build) {
        return new SdnCatalogueEntry(id, "Script " + id, "ada", "bob", "1", "2", "", "", "",
                false, true, true, false, null, null, build);
    }

    private Path file() {
        return home.resolve(InstalledScriptsLedger.FILE_NAME);
    }

    @Test
    void find_noFile_isEmpty() {
        InstalledScriptsLedger ledger = ledgerAt(FIRST);

        assertEquals(Optional.empty(), ledger.find(WOODCUTTER));
        assertTrue(ledger.all().isEmpty());
    }

    @Test
    void record_thenANewLedgerReadsItBack() throws IOException {
        ledgerAt(FIRST).record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));

        Optional<InstalledSdnScript> read = ledgerAt(SECOND).find(WOODCUTTER);

        assertEquals(Optional.of(new InstalledSdnScript("7", NEW_BUILD, FIRST)), read);
    }

    @Test
    void record_unknownBuild_roundTripsAsNullNotZero() throws IOException {
        ledgerAt(FIRST).record(Map.of(WOODCUTTER, entry("7", null)));

        assertNull(ledgerAt(SECOND).find(WOODCUTTER).orElseThrow().installedBuild());
    }

    @Test
    void record_sameClassAgain_replacesTheEarlierInstall() throws IOException {
        ledgerAt(FIRST).record(Map.of(WOODCUTTER, entry("7", OLD_BUILD)));
        ledgerAt(SECOND).record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));

        assertEquals(new InstalledSdnScript("7", NEW_BUILD, SECOND),
                ledgerAt(SECOND).find(WOODCUTTER).orElseThrow());
    }

    @Test
    void record_keepsOtherScriptsRecords() throws IOException {
        InstalledScriptsLedger ledger = ledgerAt(FIRST);
        ledger.record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));
        ledger.record(Map.of(FLETCHER, entry("12", OLD_BUILD)));

        Map<String, InstalledSdnScript> all = ledgerAt(SECOND).all();

        assertEquals(2, all.size());
        assertEquals("7", all.get(WOODCUTTER).catalogueId());
        assertEquals("12", all.get(FLETCHER).catalogueId());
    }

    /**
     * Two hosts can run at once. Each reads the ledger before recording, so one
     * host's install is not erased by another that loaded the file earlier.
     */
    @Test
    void record_keepsWhatAnotherLedgerWroteSinceThisOneLoaded() throws IOException {
        InstalledScriptsLedger mine = ledgerAt(FIRST);
        assertTrue(mine.all().isEmpty(), "loads the (absent) file first");
        ledgerAt(FIRST).record(Map.of(FLETCHER, entry("12", OLD_BUILD)));

        mine.record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));

        assertEquals(2, ledgerAt(SECOND).all().size());
        assertEquals(2, mine.all().size(), "the recording ledger sees the merged result too");
    }

    @Test
    void record_leavesOnlyTheLedgerFile() throws IOException {
        ledgerAt(FIRST).record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));

        try (Stream<Path> files = Files.list(home)) {
            assertEquals(List.of(file()), files.toList());
        }
    }

    @Test
    void all_isACopyTheCallerCannotChange() throws IOException {
        InstalledScriptsLedger ledger = ledgerAt(FIRST);
        ledger.record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));
        Map<String, InstalledSdnScript> all = ledger.all();

        assertThrows(UnsupportedOperationException.class, () -> all.remove(WOODCUTTER));
    }

    @Test
    void find_corruptFile_isEmptyRatherThanThrowing() throws IOException {
        Files.writeString(file(), "{not json");

        assertEquals(Optional.empty(), ledgerAt(FIRST).find(WOODCUTTER));
    }

    @Test
    void find_malformedRecords_skipsThemAndKeepsTheRest() throws IOException {
        Files.writeString(file(), """
                {"version":1,"scripts":{
                  "com.example.NoId":{"installedBuild":3,"installedAt":"2026-09-01T10:00:00Z"},
                  "com.example.BadTime":{"catalogueId":"4","installedAt":"yesterday"},
                  "com.example.NotAnObject":5,
                  "com.example.Woodcutter":{"catalogueId":"7","installedBuild":"seven",
                                            "installedAt":"2026-09-01T10:00:00Z"}}}""");

        Map<String, InstalledSdnScript> all = ledgerAt(FIRST).all();

        assertAll(
                () -> assertEquals(1, all.size()),
                () -> assertEquals(new InstalledSdnScript("7", null, FIRST), all.get(WOODCUTTER),
                        "a wrong-typed build reads as unknown, not as a lost record"));
    }

    @Test
    void record_overACorruptFile_replacesIt() throws IOException {
        Files.writeString(file(), "{not json");

        ledgerAt(FIRST).record(Map.of(WOODCUTTER, entry("7", NEW_BUILD)));

        assertEquals("7", ledgerAt(SECOND).find(WOODCUTTER).orElseThrow().catalogueId());
    }
}
