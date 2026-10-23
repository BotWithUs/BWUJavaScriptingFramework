package com.botwithus.bot.cli.gui.pages.settings;

import java.util.List;
import java.util.Optional;

/**
 * Everything the Settings page draws in one frame. Immutable.
 *
 * @param save       the header's saved / saving / failed line
 * @param configFile the settings file, as the header shows it
 * @param sections   every section in page order, each with all its items
 * @param note       what the last button did, if anything
 */
public record SettingsView(SaveLine save, String configFile, List<SectionView> sections, Optional<ActionNote> note) {

    public SettingsView {
        sections = List.copyOf(sections);
    }
}
