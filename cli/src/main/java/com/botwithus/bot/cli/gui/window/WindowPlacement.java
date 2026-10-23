package com.botwithus.bot.cli.gui.window;

import java.util.Objects;

/**
 * Where the window sits: its normal (restored) bounds, and whether it is
 * maximised over them. A maximised window keeps the bounds it restores to.
 */
public record WindowPlacement(WindowRect normal, boolean isMaximised) {

    public WindowPlacement {
        Objects.requireNonNull(normal, "normal");
    }
}
