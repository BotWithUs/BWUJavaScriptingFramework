package com.botwithus.bot.api.draw;

/**
 * Turns a space and a style into a concrete {@link DrawCommand}.
 *
 * <p>The seam that lets one {@link DrawBuilder} serve every primitive: the
 * geometry is captured when the builder is created and the presentation is
 * applied when it is submitted, so there is one fluent chain rather than six
 * near-identical ones.</p>
 */
@FunctionalInterface
public interface DrawCommandFactory {

    /** Build the command this factory was created for. */
    DrawCommand.Primitive create(DrawSpace space, DrawStyle style);
}
