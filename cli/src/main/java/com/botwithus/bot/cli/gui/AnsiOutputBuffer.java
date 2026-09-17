package com.botwithus.bot.cli.gui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe output buffer that stores terminal output as {@link OutputLine} entries.
 * Includes an ANSI-parsing PrintStream for drop-in replacement of the old AnsiStyledPrintStream.
 */
public final class AnsiOutputBuffer {

    private static final int MAX_LINES = 10_000;

    private final List<OutputLine> lines = new ArrayList<>();
    private volatile boolean dirty = true;
    private final PrintStream printStream;

    public AnsiOutputBuffer() {
        this.printStream = new PrintStream(new AnsiParsingOutputStream(), true, StandardCharsets.UTF_8);
    }

    /** Get the PrintStream that parses ANSI codes and appends to this buffer. */
    public PrintStream getPrintStream() {
        return printStream;
    }

    /** Get a snapshot of all lines for rendering. Clears the dirty flag. */
    public List<OutputLine> snapshot() {
        synchronized (lines) {
            dirty = false;
            return new ArrayList<>(lines);
        }
    }

    /** Whether the buffer has changed since the last snapshot. */
    public boolean isDirty() {
        return dirty;
    }

    /** Append an image line. Thread-safe. */
    public void appendImage(int texId, int width, int height) {
        synchronized (lines) {
            lines.add(OutputLine.image(texId, width, height));
            pruneIfNeeded();
            dirty = true;
        }
    }

    /**
     * Insert a progress line. Returns the OutputLine handle for later completion.
     */
    public OutputLine insertProgress(String label) {
        OutputLine line = OutputLine.progress(label);
        synchronized (lines) {
            lines.add(line);
            pruneIfNeeded();
            dirty = true;
        }
        return line;
    }

    /** Complete a progress line by replacing it with an image. */
    public void completeProgressWithImage(OutputLine handle, int texId, int width, int height) {
        synchronized (lines) {
            int idx = lines.indexOf(handle);
            if (idx >= 0) {
                lines.set(idx, OutputLine.image(texId, width, height));
            }
            dirty = true;
        }
    }

    /** Complete a progress line by replacing it with a text message. */
    public void completeProgressWithText(OutputLine handle, String message, float r, float g, float b) {
        synchronized (lines) {
            int idx = lines.indexOf(handle);
            if (idx >= 0) {
                lines.set(idx, OutputLine.text(message, r, g, b));
            }
            dirty = true;
        }
    }

    /**
     * Insert a stream line. Returns the OutputLine handle for texture updates and removal.
     */
    public OutputLine insertStream(String key, String label, int texId, int width, int height) {
        OutputLine line = OutputLine.stream(key, label, texId, width, height);
        synchronized (lines) {
            lines.add(line);
            pruneIfNeeded();
            dirty = true;
        }
        return line;
    }

    /** Update the texture on a stream line. */
    public void updateStreamTexture(OutputLine handle, int newTexId) {
        handle.setTextureId(newTexId);
        dirty = true;
    }

    /** Remove a stream line from the buffer. */
    public void removeStreamLine(OutputLine handle) {
        synchronized (lines) {
            lines.remove(handle);
            dirty = true;
        }
    }

    /** Clear all lines. */
    public void clear() {
        synchronized (lines) {
            lines.clear();
            dirty = true;
        }
    }

    private void pruneIfNeeded() {
        // Caller must hold lock on `lines`
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
    }

    // ---- ANSI parsing output stream ----

    private class AnsiParsingOutputStream extends OutputStream {

        private enum State { NORMAL, ESCAPE, CSI }

        private final ByteArrayOutputStream textBuffer = new ByteArrayOutputStream();
        private final StringBuilder csiParams = new StringBuilder();
        private final List<OutputLine.Segment> pendingSegments = new ArrayList<>();

        private State state = State.NORMAL;
        private float fgR = ImGuiTheme.TEXT_R;
        private float fgG = ImGuiTheme.TEXT_G;
        private float fgB = ImGuiTheme.TEXT_B;
        private boolean bold = false;
        private boolean dim = false;

        @Override
        public void write(int b) {
            switch (state) {
                case NORMAL -> {
                    if (b == 0x1B) {
                        flushSegment();
                        state = State.ESCAPE;
                    } else if (b == '\n') {
                        flushSegment();
                        flushLine();
                    } else {
                        textBuffer.write(b);
                    }
                }
                case ESCAPE -> {
                    if (b == '[') {
                        state = State.CSI;
                        csiParams.setLength(0);
                    } else {
                        state = State.NORMAL;
                    }
                }
                case CSI -> {
                    if (b >= 0x30 && b <= 0x3F) {
                        csiParams.append((char) b);
                    } else if (b >= 0x40 && b <= 0x7E) {
                        processCsi((char) b);
                        state = State.NORMAL;
                    } else {
                        state = State.NORMAL;
                    }
                }
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(buf[i] & 0xFF);
            }
        }

        @Override
        public void flush() {
            flushSegment();
            if (!pendingSegments.isEmpty()) {
                flushLine();
            }
        }

        private void flushSegment() {
            if (textBuffer.size() == 0) {
                return;
            }
            String text = textBuffer.toString(StandardCharsets.UTF_8);
            textBuffer.reset();

            float a = dim ? 0.6f : 1.0f;
            pendingSegments.add(new OutputLine.Segment(text, fgR, fgG, fgB, a, bold));
        }

        private void flushLine() {
            if (pendingSegments.isEmpty()) {
                // Empty newline — add blank line
                synchronized (lines) {
                    lines.add(OutputLine.text(Collections.emptyList()));
                    pruneIfNeeded();
                    dirty = true;
                }
            } else {
                OutputLine line = OutputLine.text(new ArrayList<>(pendingSegments));
                pendingSegments.clear();
                synchronized (lines) {
                    lines.add(line);
                    pruneIfNeeded();
                    dirty = true;
                }
            }
        }

        private void processCsi(char finalByte) {
            switch (finalByte) {
                case 'm' -> processSgr();
                case 'J' -> {
                    String param = csiParams.toString();
                    if ("2".equals(param)) {
                        AnsiOutputBuffer.this.clear();
                    }
                }
                default -> { /* ignore other CSI sequences */ }
            }
        }

        private void processSgr() {
            String params = csiParams.toString();
            if (params.isEmpty()) {
                resetAttributes();
                return;
            }

            String[] codes = params.split(";");
            for (String code : codes) {
                int n;
                try {
                    n = Integer.parseInt(code);
                } catch (NumberFormatException e) {
                    continue;
                }

                switch (n) {
                    case 0 -> resetAttributes();
                    case 1 -> bold = true;
                    case 2 -> dim = true;
                    case 22 -> { bold = false; dim = false; }
                    default -> {
                        int mapped = n;
                        if (n >= 90 && n <= 97) {
                            mapped = n - 60; // bright → normal
                        }
                        if (mapped >= 30 && mapped <= 37) {
                            float[] c = ImGuiTheme.ansiColorFloat(mapped);
                            fgR = c[0]; fgG = c[1]; fgB = c[2];
                        } else if (n == 39) {
                            fgR = ImGuiTheme.TEXT_R;
                            fgG = ImGuiTheme.TEXT_G;
                            fgB = ImGuiTheme.TEXT_B;
                        }
                    }
                }
            }
        }

        private void resetAttributes() {
            fgR = ImGuiTheme.TEXT_R;
            fgG = ImGuiTheme.TEXT_G;
            fgB = ImGuiTheme.TEXT_B;
            bold = false;
            dim = false;
        }
    }
}
