package com.botwithus.bot.cli.gui.window;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKey;
import com.botwithus.bot.cli.settings.SettingKeys;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes the window's placement as the {@code ui.window.*} settings.
 *
 * <p>Saving goes through {@link HostSettings}, whose debounce turns the stream of
 * placements a drag produces into one write once the window comes to rest.</p>
 */
public final class WindowPlacementStore {

    private static final List<SettingKey<Long>> BOUNDS = List.of(
            SettingKeys.WINDOW_X, SettingKeys.WINDOW_Y, SettingKeys.WINDOW_WIDTH, SettingKeys.WINDOW_HEIGHT);

    private final HostSettings settings;

    public WindowPlacementStore(HostSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * The saved placement, or empty when the bounds were never saved. Bounds only
     * partly present count as never saved: half a rectangle says nothing about
     * where the window was.
     */
    public Optional<WindowPlacement> load() {
        if (!BOUNDS.stream().allMatch(settings::isExplicit)) {
            return Optional.empty();
        }
        WindowRect normal = new WindowRect(
                Math.toIntExact(settings.get(SettingKeys.WINDOW_X)),
                Math.toIntExact(settings.get(SettingKeys.WINDOW_Y)),
                Math.toIntExact(settings.get(SettingKeys.WINDOW_WIDTH)),
                Math.toIntExact(settings.get(SettingKeys.WINDOW_HEIGHT)));
        return Optional.of(new WindowPlacement(normal, settings.get(SettingKeys.WINDOW_MAXIMISED)));
    }

    /** Saves {@code placement}; the file is written once the settings' debounce settles. */
    public void save(WindowPlacement placement) {
        WindowRect normal = placement.normal();
        settings.set(SettingKeys.WINDOW_X, (long) normal.x());
        settings.set(SettingKeys.WINDOW_Y, (long) normal.y());
        settings.set(SettingKeys.WINDOW_WIDTH, (long) normal.width());
        settings.set(SettingKeys.WINDOW_HEIGHT, (long) normal.height());
        settings.set(SettingKeys.WINDOW_MAXIMISED, placement.isMaximised());
    }
}
