package com.botwithus.bot.core.alerts;

/**
 * What an alert is about. Each kind is one row of the Integrations event grid,
 * which decides per service whether the kind is sent there.
 */
public enum AlertKind {

    CLIENT_LOST("clientLost", "Client stops responding", "the connection dropped",
            "Client stopped responding", true, false),
    CLIENT_CLOSED("clientClosed", "Client closed", "the game exited",
            "Client closed", true, false),
    CLIENT_BACK("clientBack", "Client comes back", "same account reconnected",
            "Client came back", false, false),
    SCRIPT_CRASH("scriptCrash", "Script crashes", "",
            "Script crashed", true, true),
    SCRIPT_STALL("scriptStall", "Script stalls", "one loop ran too long",
            "Script stalled", true, false),
    JAR_LOAD_FAILED("jarLoadFailed", "A JAR fails to load", "",
            "JAR failed to load", true, false),
    MANAGEMENT_ACTION("managementAction", "Management script acts", "e.g. a break starts",
            "Management script acted", false, false),
    DAILY_SUMMARY("dailySummary", "Daily summary", "runtime per client",
            "Daily summary", false, false);

    private final String id;
    private final String label;
    private final String detail;
    private final String title;
    private final boolean isProblem;
    private final boolean isPassingQuietHours;

    AlertKind(String id, String label, String detail, String title,
              boolean isProblem, boolean isPassingQuietHours) {
        this.id = id;
        this.label = label;
        this.detail = detail;
        this.title = title;
        this.isProblem = isProblem;
        this.isPassingQuietHours = isPassingQuietHours;
    }

    /** Id used in setting names, e.g. {@code scriptCrash}. */
    public String id() {
        return id;
    }

    /** The event-grid row label, e.g. {@code Script crashes}. */
    public String label() {
        return label;
    }

    /** The grid row's second line, e.g. {@code the game exited}; empty when there is none. */
    public String detail() {
        return detail;
    }

    /** Title of a message about one alert of this kind, e.g. {@code Script crashed}. */
    public String title() {
        return title;
    }

    /** Whether something went wrong: ntfy sends it at high priority, Discord may mention {@code @here}. */
    public boolean isProblem() {
        return isProblem;
    }

    /** Whether it is still sent during quiet hours. Only crashes are. */
    public boolean isPassingQuietHours() {
        return isPassingQuietHours;
    }
}
