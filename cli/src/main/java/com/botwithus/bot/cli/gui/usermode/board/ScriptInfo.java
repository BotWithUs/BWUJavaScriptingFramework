package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.ScriptCategory;

import java.util.Locale;

/**
 * What the Normal-mode screens show about a script: its manifest plus the two
 * facts the picker and inspector need — how many settings it declares and
 * whether it draws its own UI.
 *
 * @param settingsCount number of {@code ConfigField}s the script declares
 * @param hasCustomUi   whether {@code getUI()} returns a UI
 */
public record ScriptInfo(
        String name,
        String author,
        String version,
        ScriptCategory category,
        String description,
        int settingsCount,
        boolean hasCustomUi) {

    /** "by Author · v1.0", skipping whichever half is blank. */
    public String byline() {
        StringBuilder sb = new StringBuilder();
        if (!author.isBlank()) {
            sb.append("by ").append(author);
        }
        if (!version.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append('v').append(version);
        }
        return sb.toString();
    }

    /** Category label in sentence case, e.g. {@code WOODCUTTING} → "Woodcutting". */
    public String categoryLabel() {
        String raw = category.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
