package com.botwithus.bot.api.draw;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client-side half of the producer's input contract.
 *
 * <p>These limits are checked here so a script author sees the failure at the
 * call site rather than as a {@code dropped} count from a batch that has already
 * gone — and, for the key length in particular, because an over-long key is one
 * of the producer errors that does not increment {@code dropped} at all, so no
 * amount of reading the reply would have found it.</p>
 *
 * <p>Both length limits are deliberately measured the way the producer measures
 * them, which is not the same unit for both: a key is bytes, text is UTF-16
 * units. The two tests below would pass with the units swapped for ASCII, which
 * is why each uses a string where the units disagree.</p>
 */
class DrawCommandTest {

    private static final DrawStyle STYLE = DrawStyle.DEFAULT;

    /**
     * Enough two-byte characters to exceed {@link DrawLimits#MAX_KEY_BYTES} in UTF-8
     * while staying under it by character count — the pair of facts the key-length
     * test needs in order to prove which unit is being measured.
     */
    private static final int ACCENTS_OVER_THE_BYTE_LIMIT = 24;

    private static DrawCommand.Text text(String body) {
        return new DrawCommand.Text("k", DrawSpace.SCREEN, STYLE, 0, 0, body);
    }

    private static DrawCommand.Rect rect(String key) {
        return new DrawCommand.Rect(key, DrawSpace.SCREEN, STYLE, 0, 0, 1, 1);
    }

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    @Test
    void key_atTheLimit_isAccepted() {
        assertEquals(DrawLimits.MAX_KEY_BYTES,
                rect(repeat("k", DrawLimits.MAX_KEY_BYTES)).key().length());
    }

    @Test
    void key_pastTheLimit_isRejected() {
        String tooLong = repeat("k", DrawLimits.MAX_KEY_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> rect(tooLong));
    }

    /**
     * A key of 24 two-byte characters is 24 {@code String.length()} and 48 UTF-8
     * bytes. The producer counts the bytes, so this must be refused — a check
     * written against {@code length()} would wave it through.
     */
    @Test
    void key_isMeasuredInUtf8BytesNotCharacters() {
        String twentyFourAccents = repeat("é", ACCENTS_OVER_THE_BYTE_LIMIT);
        assertAll(
                () -> assertTrue(twentyFourAccents.length() <= DrawLimits.MAX_KEY_BYTES,
                        "fixture must be short by character count, or it proves nothing"),
                () -> assertThrows(IllegalArgumentException.class, () -> rect(twentyFourAccents)));
    }

    @Test
    void key_empty_isRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> rect("")),
                () -> assertThrows(IllegalArgumentException.class, () -> rect(null)));
    }

    @Test
    void text_atTheLimit_isAccepted() {
        assertEquals(DrawLimits.MAX_TEXT_LENGTH,
                text(repeat("a", DrawLimits.MAX_TEXT_LENGTH)).text().length());
    }

    @Test
    void text_pastTheLimit_isRejected() {
        String tooLong = repeat("a", DrawLimits.MAX_TEXT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> text(tooLong));
    }

    /**
     * Text is converted into a fixed wide buffer producer-side, so the cap is in
     * UTF-16 units. 127 two-byte characters are 254 UTF-8 bytes and must still be
     * accepted — a check written against the byte length would refuse them.
     */
    @Test
    void text_isMeasuredInUtf16UnitsNotBytes() {
        String longInBytesShortInUnits = repeat("é", DrawLimits.MAX_TEXT_LENGTH);
        assertEquals(DrawLimits.MAX_TEXT_LENGTH, text(longInBytesShortInUnits).text().length());
    }

    @Test
    void poly_belowTheMinimumPointCount_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawCommand.Poly("k", DrawSpace.SCREEN, STYLE, List.of(1, 2)));
    }

    @Test
    void poly_pastTheMaximumPointCount_isRejected() {
        List<Integer> tooMany = new ArrayList<>();
        for (int i = 0; i <= DrawLimits.MAX_POLY_POINTS; i++) {
            tooMany.add(i);
            tooMany.add(i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new DrawCommand.Poly("k", DrawSpace.SCREEN, STYLE, tooMany));
    }

    @Test
    void poly_withAnOddNumberOfValues_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawCommand.Poly("k", DrawSpace.SCREEN, STYLE, List.of(1, 2, 3)));
    }

    @Test
    void poly_copiesItsPointsSoALaterMutationCannotChangeWhatWasDrawn() {
        List<Integer> mutable = new ArrayList<>(List.of(1, 2, 3, 4));
        DrawCommand.Poly poly = new DrawCommand.Poly("k", DrawSpace.SCREEN, STYLE, mutable);

        mutable.set(0, 99);

        assertAll(
                () -> assertNotSame(mutable, poly.points()),
                () -> assertEquals(List.of(1, 2, 3, 4), poly.points()));
    }

    @Test
    void thickness_outsideTheProducersRange_isRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawStyle(0, DrawLimits.MIN_THICKNESS - 1, false, false, 0, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawStyle(0, DrawLimits.MAX_THICKNESS + 1, false, false, 0, 1)));
    }

    @Test
    void ttl_negative_isRejectedBecauseZeroAlreadyMeansUntilCleared() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawStyle(0, 1, false, false, 0, -1));
    }

    /**
     * The auto key format is the producer's contract, not a convenience: a caller
     * who omits a key has no other way to name the highlight in order to clear it.
     * A live test checks this against a running producer; this one pins the format
     * so a careless edit here is caught without one.
     */
    @Test
    void componentAutoKey_matchesTheProducersFormat() {
        assertEquals("comp:1473:5", DrawCommand.ComponentTarget.autoKey(1473, 5));
    }

    @Test
    void everyVariant_reportsItsOwnKind() {
        assertAll(
                () -> assertSame(DrawKind.RECT, rect("k").kind()),
                () -> assertSame(DrawKind.TEXT, text("hi").kind()),
                () -> assertSame(DrawKind.LINE,
                        new DrawCommand.Line("k", DrawSpace.SCREEN, STYLE, 0, 0, 1, 1).kind()),
                () -> assertSame(DrawKind.ELLIPSE,
                        new DrawCommand.Ellipse("k", DrawSpace.SCREEN, STYLE, 0, 0, 1, 1).kind()),
                () -> assertSame(DrawKind.POLY,
                        new DrawCommand.Poly("k", DrawSpace.SCREEN, STYLE, List.of(1, 2, 3, 4))
                                .kind()),
                () -> assertSame(DrawKind.COMPONENT,
                        new DrawCommand.ComponentTarget("k", DrawSpace.SCREEN, STYLE, 1, 2).kind()));
    }

    @Test
    void wireNames_roundTripThroughTheirOwnParsers() {
        assertAll(
                () -> assertSame(DrawKind.ELLIPSE, DrawKind.fromWireName("ellipse")),
                () -> assertSame(DrawSpace.WORLD, DrawSpace.fromWireName("world")),
                () -> assertSame(DrawSpace.SCREEN, DrawSpace.fromWireName("screen")));
    }

    @Test
    void anUnknownProducerKind_decodesToNullRatherThanThrowing() {
        assertNull(DrawKind.fromWireName("hologram"));
    }
}
