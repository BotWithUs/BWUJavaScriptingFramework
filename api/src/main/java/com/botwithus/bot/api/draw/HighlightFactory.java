package com.botwithus.bot.api.draw;

/**
 * Turns a style and a caption into a concrete {@link DrawCommand.Highlight}.
 *
 * <p>The seam that lets one {@link HighlightBuilder} serve all four named
 * highlights: <i>what</i> is being marked — an entity reference, a tile, a block of
 * tiles — is captured when the builder is created, and the presentation is applied
 * when it is submitted.</p>
 *
 * <p>It takes no {@link DrawSpace}, unlike {@link DrawCommandFactory}, and that
 * absence is the point: a highlight's space is not a caller choice. The producer
 * forces {@code world} before it validates anything, so there is nothing for a
 * builder to decide and no way for one to offer the decision.</p>
 */
@FunctionalInterface
public interface HighlightFactory {

    /** Build the highlight this factory was created for. */
    DrawCommand.Highlight create(DrawStyle style, DrawCaption caption);
}
