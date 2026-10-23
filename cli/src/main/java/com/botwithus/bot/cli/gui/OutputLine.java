package com.botwithus.bot.cli.gui;

import java.util.List;

/**
 * Data model for a single line in the terminal output.
 * Each line has a type and type-specific data.
 */
public final class OutputLine {

    /**
     * A run of text in one style.
     *
     * @param color packed imgui colour, alpha included
     * @param bold  drawn a little lighter, the console's stand-in for a bold face
     */
    public record Segment(String text, int color, boolean bold) {}

    public enum Type { TEXT, IMAGE, PROGRESS, STREAM }

    private final Type type;

    // TEXT
    private final List<Segment> segments;

    // IMAGE / STREAM
    private volatile int textureId;
    private final int imageWidth;
    private final int imageHeight;

    // PROGRESS
    private final String label;
    private volatile float progress; // -1 = indeterminate

    // STREAM
    private final String streamKey;

    // Flags
    private volatile boolean removed;

    private OutputLine(Type type, List<Segment> segments, int textureId, int w, int h,
                       String label, float progress, String streamKey) {
        this.type = type;
        this.segments = segments;
        this.textureId = textureId;
        this.imageWidth = w;
        this.imageHeight = h;
        this.label = label;
        this.progress = progress;
        this.streamKey = streamKey;
    }

    // --- Static factories ---

    public static OutputLine text(List<Segment> segments) {
        return new OutputLine(Type.TEXT, List.copyOf(segments), 0, 0, 0, null, 0, null);
    }

    public static OutputLine text(String plainText, int color) {
        return text(List.of(new Segment(plainText, color, false)));
    }

    public static OutputLine image(int texId, int width, int height) {
        return new OutputLine(Type.IMAGE, null, texId, width, height, null, 0, null);
    }

    public static OutputLine progress(String label) {
        return new OutputLine(Type.PROGRESS, null, 0, 0, 0, label, -1f, null);
    }

    public static OutputLine stream(String key, String label, int texId, int width, int height) {
        return new OutputLine(Type.STREAM, null, texId, width, height, label, 0, key);
    }

    // --- Accessors ---

    public Type getType() { return type; }
    public List<Segment> getSegments() { return segments; }
    public int getTextureId() { return textureId; }
    public int getImageWidth() { return imageWidth; }
    public int getImageHeight() { return imageHeight; }
    public String getLabel() { return label; }
    public float getProgress() { return progress; }
    public String getStreamKey() { return streamKey; }
    public boolean isRemoved() { return removed; }

    // --- Mutable setters for live updates ---

    public void setTextureId(int texId) { this.textureId = texId; }
    public void setProgress(float progress) { this.progress = progress; }
    public void markRemoved() { this.removed = true; }
}
