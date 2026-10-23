package com.botwithus.bot.cli.gui.preview;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * DEV ONLY — never shipped. Decides whether a captured preview frame shows a
 * page at all. The shell's top bar, sidebar and status bar are drawn whatever
 * the page does, so the frame is judged by its body: the middle of the window,
 * clear of the sidebar and both bars. Text and borders there are antialiased,
 * so a drawn page has hundreds of distinct colours in it; a page that drew
 * nothing leaves the background showing through, one colour or a handful.
 */
final class FrameCheck {

    /** The quietest page in the preview set has 168 colours in its body; nothing drawn has one. */
    static final int MIN_BODY_COLOURS = 32;

    private static final float BODY_LEFT = 0.30f;
    private static final float BODY_RIGHT = 0.95f;
    private static final float BODY_TOP = 0.15f;
    private static final float BODY_BOTTOM = 0.85f;

    private FrameCheck() {}

    /** Why {@code frame} looks empty, or nothing when a page was drawn in it. */
    static Optional<String> problem(BufferedImage frame) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        int colours = distinctColours(frame, (int) (w * BODY_LEFT), (int) (h * BODY_TOP),
                (int) (w * BODY_RIGHT), (int) (h * BODY_BOTTOM));
        if (colours >= MIN_BODY_COLOURS) {
            return Optional.empty();
        }
        return Optional.of("the page body has " + colours + " colour(s), fewer than " + MIN_BODY_COLOURS
                + ", so nothing was drawn there");
    }

    /** Counts the distinct RGB values in the rectangle [x0, x1) × [y0, y1). */
    private static int distinctColours(BufferedImage frame, int x0, int y0, int x1, int y1) {
        Set<Integer> seen = new HashSet<>();
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                seen.add(frame.getRGB(x, y));
            }
        }
        return seen.size();
    }
}
