package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeldAlertsTest {

    private static final Instant AT = Instant.parse("2026-09-26T23:30:00Z");

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(HeldAlerts.FILE_NAME);
    }

    @Test
    void whatIsHeld_isThereAfterOpeningTheFileAgain() {
        Alert lost = new Alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding", "", AT);
        Alert stalled = new Alert(AlertKind.SCRIPT_STALL, "Fishing stalled", "line one\nline \"two\"", AT);
        HeldAlerts first = HeldAlerts.open(file());
        first.add(lost);
        first.add(stalled);

        HeldAlerts.Batch batch = HeldAlerts.open(file()).takeAll();

        assertEquals(List.of(lost, stalled), batch.kept());
        assertEquals(Map.of(), batch.overflow());
    }

    @Test
    void pastTheCap_alertsAreCountedByKind_andTheCountSurvivesARestart() {
        HeldAlerts held = HeldAlerts.open(file());
        for (int i = 0; i < HeldAlerts.MAX_KEPT; i++) {
            held.add(new Alert(AlertKind.CLIENT_LOST, "C" + i, AT));
        }
        held.add(new Alert(AlertKind.SCRIPT_STALL, "late", AT));
        held.add(new Alert(AlertKind.SCRIPT_STALL, "later", AT));
        held.add(new Alert(AlertKind.CLIENT_BACK, "back", AT));

        HeldAlerts.Batch batch = HeldAlerts.open(file()).takeAll();

        assertEquals(HeldAlerts.MAX_KEPT, batch.kept().size());
        assertEquals("C0", batch.kept().getFirst().headline(), "the oldest are kept");
        assertEquals(Map.of(AlertKind.SCRIPT_STALL, 2, AlertKind.CLIENT_BACK, 1), batch.overflow());
        assertEquals(2, batch.overflowWhere(kind -> kind == AlertKind.SCRIPT_STALL));
    }

    @Test
    void takeAll_emptiesTheStoreAndRemovesTheFile() {
        HeldAlerts held = HeldAlerts.open(file());
        held.add(new Alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding", AT));
        assertTrue(Files.exists(file()));

        held.takeAll();

        assertTrue(held.isEmpty());
        assertFalse(Files.exists(file()));
        assertTrue(HeldAlerts.open(file()).isEmpty());
    }

    @Test
    void anUnreadableFile_isMovedAside_andNothingIsHeld() throws Exception {
        Files.writeString(file(), "{ not json", StandardCharsets.UTF_8);

        HeldAlerts held = HeldAlerts.open(file());

        assertTrue(held.isEmpty());
        assertTrue(Files.exists(dir.resolve(HeldAlerts.FILE_NAME + ".corrupt")));
    }

    @Test
    void anEntryOfAnUnknownKind_isSkipped_andTheRestAreKept() throws Exception {
        Files.writeString(file(), """
                {"version": 1, "alerts": [
                  {"kind": "noSuchKind", "headline": "x", "detail": "", "at": "2026-09-26T23:30:00Z"},
                  {"kind": "clientLost", "headline": "Hollowmere stopped responding", "detail": "",
                   "at": "2026-09-26T23:30:00Z"}
                ], "overflow": {"noSuchKind": 4, "scriptStall": 2}}
                """, StandardCharsets.UTF_8);

        HeldAlerts.Batch batch = HeldAlerts.open(file()).takeAll();

        assertEquals(List.of(new Alert(AlertKind.CLIENT_LOST, "Hollowmere stopped responding", AT)), batch.kept());
        assertEquals(Map.of(AlertKind.SCRIPT_STALL, 2), batch.overflow());
    }
}
