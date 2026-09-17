package com.botwithus.bot.cli.gui;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises how the console output buffer splits writes into rendered
 * lines. This is the contract the {@code player} command (and any command
 * printing to {@code ctx.out()} in the GUI) depends on: a fully-built string
 * emitted with a single {@code println} renders as exactly one line, whereas a
 * {@code printf} fragments into one line per {@link java.util.Formatter} chunk.
 */
class AnsiOutputBufferLineTest {

    private static List<OutputLine> textLines(AnsiOutputBuffer buffer) {
        return buffer.snapshot().stream()
                .filter(line -> line.getType() == OutputLine.Type.TEXT)
                .toList();
    }

    private static String text(OutputLine line) {
        StringBuilder sb = new StringBuilder();
        for (OutputLine.Segment seg : line.getSegments()) {
            sb.append(seg.text());
        }
        // The platform line separator carries a CR into the last segment on
        // Windows; ImGui skips it at render time, so strip it for comparison.
        return sb.toString().replace("\r", "");
    }

    @Test
    void printlnOfFormattedStringRendersAsSingleLine() {
        AnsiOutputBuffer buffer = new AnsiOutputBuffer();
        PrintStream out = buffer.getPrintStream();

        out.println(String.format("  Target: %d (type %d)", 0, 127));

        List<OutputLine> lines = textLines(buffer);
        assertEquals(1, lines.size(), "a formatted line must render as one console line");
        assertEquals("  Target: 0 (type 127)", text(lines.getFirst()));
    }

    @Test
    void printfFragmentsIntoMultipleLines() {
        // Documents WHY the player command builds strings before printing: a
        // direct printf to this stream fragments per format chunk. If this ever
        // stops being true (the buffer is taught to coalesce a printf into one
        // line), this test will fail — at which point the command may safely go
        // back to printf.
        AnsiOutputBuffer buffer = new AnsiOutputBuffer();
        PrintStream out = buffer.getPrintStream();

        out.printf("  Target: %d (type %d)%n", 0, 127);

        assertTrue(textLines(buffer).size() > 1,
                "printf is expected to fragment across multiple lines on this buffer");
    }
}
