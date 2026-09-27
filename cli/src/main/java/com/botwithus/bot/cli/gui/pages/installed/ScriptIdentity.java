package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.ScriptCategory;

import java.util.Locale;
import java.util.Objects;

/**
 * What a script says about itself: its manifest, plus how much it offers to
 * configure. For a Store script that is not loaded, what the catalogue says.
 *
 * @param settingsCount how many settings fields it declares
 * @param hasUi         whether it draws its own settings UI
 */
public record ScriptIdentity(String name, String version, String description, String author,
                             ScriptCategory category, int settingsCount, boolean hasUi) {

    public ScriptIdentity {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(category, "category");
    }

    /** Whether a settings link has anything to open. */
    public boolean hasSettings() {
        return settingsCount > 0 || hasUi;
    }

    /** The category as a person reads it: {@code "Woodcutting"}. */
    public String categoryLabel() {
        String lower = category.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
