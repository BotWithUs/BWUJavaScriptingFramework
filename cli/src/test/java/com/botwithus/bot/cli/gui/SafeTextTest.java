package com.botwithus.bot.cli.gui;

import org.junit.jupiter.api.Test;

import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTextTest {

    /** One unit per char, standing in for a font's measure. */
    private static final ToDoubleFunction<String> CHARS = String::length;

    @Test
    void line_turnsBreaksAndControlsIntoSingleSpaces() {
        assertEquals("Wood cutter by me", SafeText.line("Wood\ncutter\r\n\tby\u0000\u0007me", 100));
    }

    @Test
    void line_dropsInvisibleFormattingThatCouldDisguiseTheName() {
        // Right-to-left override, zero-width joiner and space, byte-order mark, and an icon-font glyph.
        assertEquals("Wood cutter", SafeText.line("Wood‮cutter‍​﻿", 100));
    }

    @Test
    void line_keepsOrdinaryTextIncludingAccentsAndEmoji() {
        assertEquals("Bûcheron 🪓 v2", SafeText.line("  Bûcheron 🪓 v2  ", 100));
    }

    @Test
    void line_capsTheLength_withAnEllipsis() {
        String shown = SafeText.line("x".repeat(500), 48);

        assertEquals(48, shown.length());
        assertTrue(shown.endsWith(SafeText.ELLIPSIS), shown);
    }

    @Test
    void line_neverSplitsASurrogatePair() {
        String shown = SafeText.line("🪓".repeat(10), 4);

        assertEquals("🪓🪓🪓" + SafeText.ELLIPSIS, shown);
    }

    @Test
    void line_ofNullOrOnlyControls_isEmpty() {
        assertEquals("", SafeText.line(null, 10));
        assertEquals("", SafeText.line("\n‮\t", 10));
    }

    @Test
    void key_keepsTheModuleAndTheClassName_whenCappingByChars() {
        String key = "com.example.wc/com.example." + "very".repeat(60) + ".Woodcutter";

        String shown = SafeText.key(key, 40);

        assertTrue(shown.length() <= 40, shown);
        assertEquals("com.example.wc/" + SafeText.ELLIPSIS + shown.substring(16), shown);
        assertTrue(shown.endsWith(".Woodcutter"), shown);
    }

    @Test
    void key_cleansAsLineDoes() {
        assertEquals("m/ evil", SafeText.key("m/\u202E\nevil", 40));
    }

    @Test
    void fitKey_leavesAKeyThatFitsAlone() {
        assertEquals("a/b.Short", SafeText.fitKey("a/b.Short", 20, CHARS));
    }

    @Test
    void fitKey_dropsTheClassPackageFirst() {
        assertEquals("tools/" + SafeText.ELLIPSIS + "Woodcutter",
                SafeText.fitKey("tools/com.example.scripts.Woodcutter", 17, CHARS));
    }

    @Test
    void fitKey_twoKeysDifferingOnlyInTheModuleHead_stayDifferent() {
        String a = "com.alpha.tools/com.example.scripts.Woodcutter";
        String b = "com.bravo.tools/com.example.scripts.Woodcutter";
        // From the width that holds the whole module and the class name. Narrower than that,
        // the module's middle has to give way and no fitting can keep every difference.
        int moduleAndName = "com.alpha.tools/".length() + (SafeText.ELLIPSIS + "Woodcutter").length();

        for (int width = moduleAndName; width < a.length(); width++) {
            String shownA = SafeText.fitKey(a, width, CHARS);
            String shownB = SafeText.fitKey(b, width, CHARS);
            assertNotEquals(shownA, shownB, "at width " + width);
            assertTrue(shownA.endsWith("Woodcutter"), shownA);
        }
    }

    @Test
    void fitKey_twoKeysDifferingOnlyAtTheModuleEnd_stayDifferent() {
        String a = "com.example.tools1/com.example.scripts.Woodcutter";
        String b = "com.example.tools2/com.example.scripts.Woodcutter";

        for (int width = 16; width < a.length(); width++) {
            assertNotEquals(SafeText.fitKey(a, width, CHARS), SafeText.fitKey(b, width, CHARS), "at width " + width);
        }
    }

    @Test
    void fitKey_twoKeysDifferingOnlyInTheClassName_stayDifferent() {
        String a = "com.example.tools/com.example.scripts.Woodcutter";
        String b = "com.example.tools/com.example.scripts.Fisher";

        for (int width = 12; width < b.length(); width++) {
            assertNotEquals(SafeText.fitKey(a, width, CHARS), SafeText.fitKey(b, width, CHARS), "at width " + width);
        }
    }

    @Test
    void fitKey_aModuleTooLongForTheWidth_losesItsMiddleNotItsEnds() {
        String shown = SafeText.fitKey("com.alpha.very.long.module.name/com.example.Woodcutter", 30, CHARS);

        assertTrue(shown.length() <= 30, shown);
        assertTrue(shown.startsWith("com.alp"), shown);
        assertTrue(shown.endsWith("/" + SafeText.ELLIPSIS + "Woodcutter"), shown);
    }
}
