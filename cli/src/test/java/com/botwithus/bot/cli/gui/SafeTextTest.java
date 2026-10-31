package com.botwithus.bot.cli.gui;

import org.junit.jupiter.api.Test;

import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void lineKeepingTail_keepsTheDistinguishingEnd() {
        String key = "com.example." + "very".repeat(60) + "/com.example.Woodcutter";

        String shown = SafeText.lineKeepingTail(key, 40);

        assertEquals(40, shown.length());
        assertTrue(shown.startsWith(SafeText.ELLIPSIS), shown);
        assertTrue(shown.endsWith("/com.example.Woodcutter"), shown);
    }

    @Test
    void lineKeepingTail_cleansAsLineDoes() {
        assertEquals("m/ evil", SafeText.lineKeepingTail("m/‮\nevil", 40));
    }

    @Test
    void keepTail_shortensFromTheFront_toFitTheWidth() {
        assertEquals("a/b.Short", SafeText.keepTail("a/b.Short", 20, CHARS));
        assertEquals(SafeText.ELLIPSIS + "Woodcutter", SafeText.keepTail("com.example/com.example.Woodcutter", 11, CHARS));
    }

    @Test
    void keepTail_twoKeysThatDifferOnlyAtTheEnd_stillLookDifferent() {
        String a = "com.example.shared.prefix/com.example.Woodcutter";
        String b = "com.example.shared.prefix/com.example.Fisher";

        String shownA = SafeText.keepTail(a, 16, CHARS);
        String shownB = SafeText.keepTail(b, 16, CHARS);

        assertTrue(!shownA.equals(shownB), shownA + " vs " + shownB);
    }
}
