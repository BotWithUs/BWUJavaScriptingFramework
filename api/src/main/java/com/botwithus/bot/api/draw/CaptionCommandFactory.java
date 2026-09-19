package com.botwithus.bot.api.draw;

/**
 * Turns a space, a style and a caption into a concrete {@link DrawCommand}.
 *
 * <p>The captioned counterpart of {@link DrawCommandFactory}: it exists as its
 * own type rather than as a wider version of that one so a shape's builder has no
 * way to supply a caption.</p>
 */
@FunctionalInterface
public interface CaptionCommandFactory {

    /**
     * Build the command this factory was created for.
     *
     * <p><b>The return type narrowed from {@link DrawCommand} to
     * {@link DrawCommand.Primitive}</b>, with the same consequence for an explicit
     * implementor as {@link DrawCommandFactory#create}: lambdas are unaffected, a declared
     * {@code DrawCommand} return must narrow.</p>
     */
    DrawCommand.Primitive create(DrawSpace space, DrawStyle style, DrawCaption caption);
}
