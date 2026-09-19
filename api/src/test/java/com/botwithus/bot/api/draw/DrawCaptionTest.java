package com.botwithus.bot.api.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The caption rules, and which of them are enforced by the type rather than checked.
 *
 * <p>The wire has three spellings of one payload — {@code text}, {@code label} and
 * {@code value} — and exactly one is legal per command; the producer rejects two
 * with {@code "value" cannot be combined with "text" or "label"} rather than
 * applying a precedence rule. <b>That error is not reachable from this host, and
 * there is deliberately no test for it</b>, because a command holds a single
 * {@link DrawCaption} and there is nowhere to put a second. The same is true of a
 * caption or a font on a shape: shapes have no caption field, so
 * {@code draw.rect(...).font(...)} does not compile. Those are compile-time facts
 * and a runtime test cannot express them — {@code DrawCodecTest} guards the
 * placement that makes them true, by asserting a shape encodes no caption keys.</p>
 *
 * <p>What is left to check here is the ranges the type cannot express, and the
 * defaults. Assertions compare whole records rather than picking fields out of
 * them, which needs no cast and pins the shape as well as the value.</p>
 */
class DrawCaptionTest {

    @Test
    void literal_defaultsToTheNormalFont() {
        assertEquals(new DrawCaption.Literal("hi", DrawFont.NORMAL), DrawCaption.of("hi"));
    }

    @Test
    void fixedPoint_defaultsToTheNormalFont() {
        assertEquals(new DrawCaption.FixedPoint(1234L, 2, DrawFont.NORMAL),
                DrawCaption.of(1234L, 2));
    }

    @Test
    void fixedPoint_carriesItsScaleAndFont() {
        assertEquals(new DrawCaption.FixedPoint(1234L, 2, DrawFont.LARGE),
                DrawCaption.of(1234L, 2, DrawFont.LARGE));
    }

    @Test
    void decimals_outsideTheProducersRange_isRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DrawCaption.of(1L, -1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DrawCaption.of(1L, DrawLimits.MAX_DECIMALS + 1)));
    }

    @Test
    void decimals_atEitherEndOfTheRange_isAccepted() {
        assertAll(
                () -> assertEquals(new DrawCaption.FixedPoint(1L, 0, DrawFont.NORMAL),
                        DrawCaption.of(1L, 0)),
                () -> assertEquals(
                        new DrawCaption.FixedPoint(1L, DrawLimits.MAX_DECIMALS, DrawFont.NORMAL),
                        DrawCaption.of(1L, DrawLimits.MAX_DECIMALS)));
    }

    @Test
    void literal_pastTheTextLimit_isRejected() {
        String tooLong = "a".repeat(DrawLimits.MAX_TEXT_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> DrawCaption.of(tooLong));
    }

    @Test
    void literal_empty_isRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> DrawCaption.of("")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DrawCaption.of(null)));
    }

    /**
     * A null style is rejected by the builder rather than carried.
     *
     * <p>It would otherwise survive the {@link DrawCaption#NONE} path, because
     * {@code None.withFont} ignores its argument, and surface later from inside
     * {@code label()} — naming the wrong call.</p>
     */
    @Test
    void font_null_isRejectedAtTheCallThatSetIt() {
        assertThrows(IllegalArgumentException.class, () -> captionBuilder().font(null));
    }

    /**
     * A font with no caption sends nothing, and that is not an error: setting the
     * style up front and the caption conditionally is a reasonable thing to write.
     */
    @Test
    void font_withoutACaption_leavesTheCaptionEmpty() {
        assertSame(DrawCaption.NONE, captionBuilder().font(DrawFont.HEADING).caption());
    }

    @Test
    void none_isTheOnlyEmptyCaption() {
        assertAll(
                () -> assertTrue(DrawCaption.NONE.isEmpty()),
                () -> assertFalse(DrawCaption.of("hi").isEmpty()),
                () -> assertFalse(DrawCaption.of(1L, 0).isEmpty()));
    }

    /** Restyling keeps the payload and swaps only the font, whichever shape it is. */
    @Test
    void withFont_keepsThePayload() {
        assertAll(
                () -> assertEquals(new DrawCaption.Literal("hi", DrawFont.HEADING),
                        DrawCaption.of("hi").withFont(DrawFont.HEADING)),
                () -> assertEquals(new DrawCaption.FixedPoint(1234L, 2, DrawFont.SMALL),
                        DrawCaption.of(1234L, 2).withFont(DrawFont.SMALL)),
                () -> assertSame(DrawCaption.NONE, DrawCaption.NONE.withFont(DrawFont.LARGE)));
    }

    /**
     * A text command must draw something. A component highlight need not — an
     * uncaptioned highlight is the ordinary case.
     */
    @Test
    void textCommand_requiresACaptionWhileAHighlightDoesNot() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new DrawCommand.Text("k", DrawSpace.SCREEN, DrawStyle.DEFAULT,
                                0, 0, DrawCaption.NONE)),
                () -> assertSame(DrawCaption.NONE,
                        new DrawCommand.ComponentTarget("k", DrawSpace.SCREEN, DrawStyle.DEFAULT,
                                1, 2, DrawCaption.NONE).caption()));
    }

    @Test
    void fontWireNames_roundTrip() {
        for (DrawFont font : DrawFont.values()) {
            assertSame(font, DrawFont.fromWireName(font.wireName()));
        }
    }

    /**
     * A style this build does not know decodes to {@link DrawFont#NORMAL} rather
     * than throwing — the producer is versioned separately and may grow one, and a
     * list decode must survive that.
     */
    @Test
    void anUnknownProducerFont_decodesToNormal() {
        assertSame(DrawFont.NORMAL, DrawFont.fromWireName("copperplate"));
    }

    /**
     * The builder chain replaces rather than accumulates, because the wire allows
     * exactly one caption. Setting a label after a value leaves a label only.
     */
    @Test
    void builderChain_replacesTheCaptionRatherThanAccumulating() {
        DrawCaption afterValueThenLabel = captionBuilder()
                .value(1234, 2)
                .label("Inventory")
                .caption();
        DrawCaption afterLabelThenValue = captionBuilder()
                .label("Inventory")
                .value(1234, 2)
                .caption();

        assertAll(
                () -> assertEquals(new DrawCaption.Literal("Inventory", DrawFont.NORMAL),
                        afterValueThenLabel),
                () -> assertEquals(new DrawCaption.FixedPoint(1234L, 2, DrawFont.NORMAL),
                        afterLabelThenValue));
    }

    /** A font set before the caption survives the caption being replaced. */
    @Test
    void builderChain_keepsTheFontAcrossACaptionChange() {
        DrawCaption caption = captionBuilder()
                .font(DrawFont.HEADING)
                .label("Inventory")
                .caption();

        assertEquals(new DrawCaption.Literal("Inventory", DrawFont.HEADING), caption);
    }

    /**
     * A caption builder with no destination. This test class lives in the same
     * package, so the package-private constructor is reachable without standing up
     * a whole {@code DrawAPI}; nothing here submits, so the target is never used.
     */
    private static DrawCaptionBuilder captionBuilder() {
        return new DrawCaptionBuilder(null, DrawBuilder.styleOnly(null),
                (space, style, caption) -> new DrawCommand.ComponentTarget(
                        "k", space, style, 1473, 5, caption),
                DrawCaption.NONE);
    }
}
