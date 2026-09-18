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
 * gone.</p>
 *
 * <p>{@link DrawStyle}'s {@code z} range is the one check with no producer-side
 * counterpart at all: the producer narrows {@code z} to 16 bits with a plain
 * cast, so an out-of-range paint order is silently truncated into a different
 * one rather than refused. See {@link DrawLimits#MIN_Z}.</p>
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

    /**
     * The producer stores {@code z} in an {@code int16_t} and narrows to it with a
     * plain cast, so an out-of-range paint order is <b>silently truncated</b> — no
     * error, no {@code dropped}, and nothing in {@code debug_draw_list} to show for
     * it. {@code z(100000)} would arrive as {@code -31072} and paint behind
     * everything instead of in front.
     *
     * <p>That makes this the one limit with no producer-side refusal for the host
     * check to agree with: the check is the only thing between a caller and a
     * silently wrong result, so it has to reject rather than clamp.</p>
     */
    @Test
    void z_pastTheProducersSixteenBitRange_isRejectedRatherThanSilentlyTruncated() {
        int truncatesToADifferentOrder = 100_000;
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawStyle(0, 1, false, false, truncatesToADifferentOrder, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawStyle(0, 1, false, false, DrawLimits.MAX_Z + 1, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawStyle(0, 1, false, false, DrawLimits.MIN_Z - 1, 1)));
    }

    /** Both ends of the producer's actual range must still be usable. */
    @Test
    void z_atEitherEndOfTheRange_isAccepted() {
        assertAll(
                () -> assertEquals(DrawLimits.MAX_Z,
                        new DrawStyle(0, 1, false, false, DrawLimits.MAX_Z, 1).z()),
                () -> assertEquals(DrawLimits.MIN_Z,
                        new DrawStyle(0, 1, false, false, DrawLimits.MIN_Z, 1).z()));
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
