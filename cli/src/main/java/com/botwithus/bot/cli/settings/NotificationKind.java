package com.botwithus.bot.cli.settings;

/**
 * The kinds of pop-up the host can show, each with its own
 * {@code notify.<id>.enabled} switch (see {@link SettingKeys#notifyEnabled}).
 */
public enum NotificationKind {
    CLIENT_LOST("clientLost", "Client stops responding", "Connection lost and reconnect attempts."),
    CLIENT_BACK("clientBack", "Client comes back", "Reconnected, or the same account relaunched."),
    SCRIPT_CRASH("scriptCrash", "Script crashes or stalls", "A script threw, or one loop ran too long."),
    LOAD_FAILED("loadFailed", "A JAR fails to load", "A script JAR in scripts/ could not be loaded.");

    private final String id;
    private final String label;
    private final String description;

    NotificationKind(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    /** The middle segment of the setting name, e.g. {@code clientLost}. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
