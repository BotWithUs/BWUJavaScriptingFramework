package com.botwithus.bot.cli.gui.window;

import java.util.Objects;
import java.util.Optional;

/**
 * Turns what the window looks like each frame into the placement worth saving,
 * and reports it only when that changes.
 *
 * <p>Two states must never be saved as the window's normal bounds: a maximised
 * window's bounds (the work area, which is not where it restores to) and a
 * minimised one's (Windows parks it at -32000, -32000). While maximised the
 * last normal bounds are kept and only the flag changes; while minimised
 * nothing is reported at all.</p>
 */
public final class PlacementTracker {

    private WindowRect lastNormal;
    private WindowPlacement lastReported;

    /** @param initial the placement the window opened with; seeing it again saves nothing */
    public PlacementTracker(WindowPlacement initial) {
        this.lastNormal = Objects.requireNonNull(initial, "initial").normal();
        this.lastReported = initial;
    }

    /**
     * Records one frame's observation.
     *
     * @param current     the window's bounds as the system reports them now
     * @param isMaximised whether it is maximised now
     * @param isIconified whether it is minimised now
     * @return the placement to save, or empty when nothing worth saving changed
     */
    public Optional<WindowPlacement> observe(WindowRect current, boolean isMaximised, boolean isIconified) {
        if (isIconified) {
            return Optional.empty();
        }
        if (!isMaximised) {
            lastNormal = current;
        }
        WindowPlacement now = new WindowPlacement(lastNormal, isMaximised);
        if (now.equals(lastReported)) {
            return Optional.empty();
        }
        lastReported = now;
        return Optional.of(now);
    }
}
