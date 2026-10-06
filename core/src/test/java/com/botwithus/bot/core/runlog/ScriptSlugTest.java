package com.botwithus.bot.core.runlog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSlugTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[BWU] Agility!|bwu-agility",
        "Agility - Anachronia|agility-anachronia",
        "Woodcutter|woodcutter",
        "--already-dashed--|already-dashed",
        "Zaros 2.0 (beta)|zaros-2-0-beta",
        "Ünïcode Names|n-code-names"})
    void slugOf(String name, String expected) {
        assertEquals(expected, ScriptSlug.of(name));
    }

    @Test
    void longName_isCutTo48_withoutATrailingDash() {
        String slug = ScriptSlug.of("a".repeat(47) + " b c d");
        assertTrue(slug.length() <= ScriptSlug.MAX_LENGTH, slug);
        assertFalse(slug.endsWith("-"), slug);
        assertEquals("a".repeat(47), slug);
    }

    @Test
    void aNameWithNothingUsable_stillGetsADirectory() {
        assertEquals(ScriptSlug.FALLBACK, ScriptSlug.of("!!!"));
        assertEquals(ScriptSlug.FALLBACK, ScriptSlug.of(null));
    }
}
