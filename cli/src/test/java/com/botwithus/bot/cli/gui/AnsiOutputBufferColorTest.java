package com.botwithus.bot.cli.gui;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the console output buffer turns ANSI SGR codes into theme colours. */
class AnsiOutputBufferColorTest {

    private static final String ESC = "\u001b[";
    private static final int SGR_BLACK = 30;
    private static final int SGR_WHITE = 37;
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final int ALPHA_SHIFT = 24;

    /** The segments of the one line {@code text} prints as, trailing CR dropped. */
    private static List<OutputLine.Segment> segmentsOf(String text) {
        AnsiOutputBuffer buffer = new AnsiOutputBuffer();
        PrintStream out = buffer.getPrintStream();
        out.print(text + "\n");
        List<OutputLine> lines = buffer.snapshot();
        assertEquals(1, lines.size(), "one line printed");
        return lines.getFirst().getSegments();
    }

    @Test
    void plainText_isTheDefaultForeground() {
        List<OutputLine.Segment> segments = segmentsOf("plain");

        assertEquals(List.of(new OutputLine.Segment("plain", ImGuiTheme.COL_FG, false)), segments);
    }

    @Test
    void eachForegroundCode_mapsToItsThemeColour() {
        List<Integer> expected = List.of(ImGuiTheme.COL_BG, ImGuiTheme.COL_DANGER, ImGuiTheme.COL_ACCENT,
                ImGuiTheme.COL_WARN, ImGuiTheme.COL_INFO, ImGuiTheme.COL_MAGENTA, ImGuiTheme.COL_CYAN,
                ImGuiTheme.COL_FG);
        StringBuilder text = new StringBuilder();
        for (int code = SGR_BLACK; code <= SGR_WHITE; code++) {
            text.append(ESC).append(code).append('m').append(code);
        }

        List<Integer> colours = segmentsOf(text.toString()).stream().map(OutputLine.Segment::color).toList();

        assertEquals(expected, colours);
    }

    @Test
    void brightCodes_useTheNormalColour_andResetsGoBackToTheDefault() {
        List<OutputLine.Segment> segments = segmentsOf(ESC + "91mbright" + ESC + "39mdefault"
                + ESC + "32mgreen" + ESC + "0mreset");

        assertAll(
                () -> assertEquals(ImGuiTheme.COL_DANGER, segments.get(0).color()),
                () -> assertEquals(ImGuiTheme.COL_FG, segments.get(1).color()),
                () -> assertEquals(ImGuiTheme.COL_ACCENT, segments.get(2).color()),
                () -> assertEquals(ImGuiTheme.COL_FG, segments.get(3).color()));
    }

    @Test
    void faintText_keepsItsColourAtLowerOpacity_andBoldIsFlagged() {
        List<OutputLine.Segment> segments = segmentsOf(ESC + "31;2mfaint" + ESC + "22;1mbold");

        OutputLine.Segment faint = segments.get(0);
        OutputLine.Segment bold = segments.get(1);
        assertAll(
                () -> assertEquals(ImGuiTheme.COL_DANGER & RGB_MASK, faint.color() & RGB_MASK),
                () -> assertTrue((faint.color() >>> ALPHA_SHIFT) < (ImGuiTheme.COL_DANGER >>> ALPHA_SHIFT),
                        "faint is fainter"),
                () -> assertFalse(faint.bold()),
                () -> assertEquals(ImGuiTheme.COL_DANGER, bold.color()),
                () -> assertTrue(bold.bold()));
    }
}
