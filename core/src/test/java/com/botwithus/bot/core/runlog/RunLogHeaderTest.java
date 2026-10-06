package com.botwithus.bot.core.runlog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogHeaderTest {

    /**
     * Copied from spec §1.1 by hand, deliberately not from {@link RunLogHeader#KEYS}:
     * a key dropped or reordered in the production list must fail here.
     */
    private static final List<String> SPEC_KEYS = List.of(
            "run_id", "host", "host_version", "protocol_version", "agent_build", "game_revision",
            "script_name", "script_version", "script_author", "script_source", "script_sha256",
            "os", "runtime", "started_at", "slot");

    private static final String RUN_ID = "3f9c1a2be0d84c1e9a7f5d2b6c0e4a11";
    private static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final Instant STARTED = Instant.parse("2026-10-06T07:12:23.529Z");

    private static RunLogHeader full() {
        return new RunLogHeader(RUN_ID, "1.0-SNAPSHOT", 22, "0123456789abcdef0123456789abcdef",
                "950-1", "Woodcutter", "1.2", "Dev", "local", SHA, "Windows 11 10.0",
                "Java 25.0.1", STARTED, 3);
    }

    @Test
    void render_hasEverySpecKey_inSpecOrder_betweenMagicAndEnd() {
        List<String> lines = full().render(Redactor.withNames(KnownNames.NONE));
        assertEquals(RunLogHeader.MAGIC, lines.getFirst());
        assertEquals(RunLogHeader.END, lines.getLast());
        List<String> keys = new ArrayList<>();
        for (String line : lines.subList(1, lines.size() - 1)) {
            keys.add(line.substring(0, line.indexOf(": ")));
        }
        assertEquals(SPEC_KEYS, keys);
    }

    @Test
    void render_values() {
        List<String> lines = full().render(Redactor.withNames(KnownNames.NONE));
        assertAll(
                () -> assertTrue(lines.contains("run_id: " + RUN_ID), "run_id survives R5"),
                () -> assertTrue(lines.contains("script_sha256: " + SHA), "sha survives R5"),
                () -> assertTrue(lines.contains("agent_build: 0123456789abcdef0123456789abcdef")),
                () -> assertTrue(lines.contains("host: java")),
                () -> assertTrue(lines.contains("protocol_version: 22")),
                () -> assertTrue(lines.contains("game_revision: 950-1"), "950-1 is not an IP"),
                () -> assertTrue(lines.contains("os: Windows 11 10.0")),
                () -> assertTrue(lines.contains("started_at: 2026-10-06T07:12:23.529Z")),
                () -> assertTrue(lines.contains("slot: 3")));
    }

    @Test
    void missingValues_areTheLiteralUnknown_neverOmitted() {
        RunLogHeader header = new RunLogHeader(null, " ", 22, null, null, "S", null, "",
                null, null, null, null, STARTED, 0);
        List<String> lines = header.render(Redactor.withNames(KnownNames.NONE));
        assertEquals(SPEC_KEYS.size() + 2, lines.size());
        for (String key : List.of("run_id", "host_version", "agent_build", "game_revision",
                "script_version", "script_author", "script_source", "script_sha256", "os", "runtime")) {
            assertTrue(lines.contains(key + ": unknown"), key);
        }
    }

    @Test
    void nonHashValues_arePutThroughEveryRule() {
        RunLogHeader header = new RunLogHeader(RUN_ID, "v", 22, null, null, "Zezima's script",
                "1", "dave.smith@example.com", "local", SHA, "os", "rt", STARTED, 1);
        List<String> lines = header.render(Redactor.withNames(
                new KnownNames(List.of("dave.smith@example.com"), List.of("Zezima"))));
        assertAll(
                () -> assertTrue(lines.contains("script_name: Player#1's script")),
                () -> assertTrue(lines.contains("script_author: Account#1")));
    }
}
