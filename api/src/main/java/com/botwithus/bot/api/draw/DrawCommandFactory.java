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

    /**
     * Build the command this factory was created for.
     *
     * <p><b>The return type narrowed from {@link DrawCommand} to
     * {@link DrawCommand.Primitive}.</b> A lambda is unaffected, since its return type is
     * inferred; an explicit {@code implements DrawCommandFactory} whose {@code create}
     * was declared to return {@code DrawCommand} no longer overrides this and must
     * narrow. The narrowing is what makes a batch unable to carry a highlight — see
     * {@link DrawCommand.Highlight}.</p>
     */
    DrawCommand.Primitive create(DrawSpace space, DrawStyle style);
}
