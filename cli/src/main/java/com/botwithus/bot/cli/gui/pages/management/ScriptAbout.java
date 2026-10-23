package com.botwithus.bot.cli.gui.pages.management;

import java.util.Objects;

/**
 * What a management script says about itself: its manifest, its class and
 * what it lets the user change.
 *
 * @param className     the script's class, fully qualified
 * @param settingsCount how many settings it declares
 * @param hasUi         whether it draws a UI of its own
 */
public record ScriptAbout(String name, String version, String description, String author, String className,
                          int settingsCount, boolean hasUi) {

    public ScriptAbout {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(className, "className");
    }

    /** "v1.0", or nothing when the script gives no version. */
    public String versionLabel() {
        return version.isBlank() ? "" : "v" + version;
    }

    /** The class's simple name, without its package or any enclosing class: {@code BreakScheduler}. */
    public String simpleClassName() {
        return className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
    }

    /** "4 fields", "4 fields + custom UI", "custom UI" or "none". */
    public String settingsLine() {
        String fields = settingsCount > 0 ? ManagementText.count(settingsCount, "field") : "";
        if (!hasUi) {
            return fields.isEmpty() ? "none" : fields;
        }
        return fields.isEmpty() ? "custom UI" : fields + " + custom UI";
    }
}
