package com.botwithus.bot.cli.gui.window;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Where the window opens, from where it was saved and the monitors there are now.
 *
 * <ul>
 *   <li>Nothing saved: maximised over the primary monitor's work area, restoring
 *       to the default size centred on it.</li>
 *   <li>Saved, and enough of it lands on a monitor to grab: on the monitor holding
 *       most of it, moved and if need be shrunk to lie inside that work area.</li>
 *   <li>Saved, but its monitor is gone or it is all but off-screen: centred on the
 *       primary monitor at its saved size.</li>
 * </ul>
 *
 * <p>The maximised flag is kept in every case, so a window maximised on a
 * monitor that is gone opens maximised on the primary one.</p>
 *
 * @param defaultWidth   restored width when nothing is saved
 * @param defaultHeight  restored height when nothing is saved
 * @param minWidth       narrowest a saved window is allowed to open
 * @param minHeight      shortest a saved window is allowed to open
 * @param minVisibleSide a saved window counts as on screen only when at least a
 *                       square this many pixels on a side of it lies on one monitor
 */
public record PlacementRestore(int defaultWidth, int defaultHeight, int minWidth, int minHeight,
                               int minVisibleSide) {

    /**
     * @param saved     the last saved placement, if any
     * @param workAreas each monitor's work area, the primary monitor first; may be empty
     */
    public WindowPlacement resolve(Optional<WindowPlacement> saved, List<WindowRect> workAreas) {
        if (saved.isEmpty()) {
            WindowRect normal = new WindowRect(0, 0, defaultWidth, defaultHeight);
            return new WindowPlacement(workAreas.isEmpty() ? normal : onPrimary(normal, workAreas), true);
        }
        WindowRect rect = atLeastMinimum(saved.get().normal());
        boolean isMaximised = saved.get().isMaximised();
        if (workAreas.isEmpty()) {
            return new WindowPlacement(rect, isMaximised);
        }
        Optional<WindowRect> home = monitorHolding(rect, workAreas);
        WindowRect restored = home.map(rect::fittedInto).orElseGet(() -> onPrimary(rect, workAreas));
        return new WindowPlacement(restored, isMaximised);
    }

    private WindowRect atLeastMinimum(WindowRect rect) {
        return new WindowRect(rect.x(), rect.y(), Math.max(minWidth, rect.width()),
                Math.max(minHeight, rect.height()));
    }

    /** The monitor holding most of {@code rect}, if it holds enough to grab. */
    private Optional<WindowRect> monitorHolding(WindowRect rect, List<WindowRect> workAreas) {
        long enough = (long) minVisibleSide * minVisibleSide;
        return workAreas.stream()
                .max(Comparator.comparingLong(rect::overlapArea))
                .filter(area -> rect.overlapArea(area) >= enough);
    }

    private static WindowRect onPrimary(WindowRect rect, List<WindowRect> workAreas) {
        WindowRect primary = workAreas.getFirst();
        return rect.fittedInto(primary).centredIn(primary);
    }
}
