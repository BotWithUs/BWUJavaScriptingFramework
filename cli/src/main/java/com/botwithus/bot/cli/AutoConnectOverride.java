package com.botwithus.bot.cli;

/**
 * A per-process override of the {@code autoConnect} setting, for developer runs.
 *
 * <p>{@code gradlew :cli:run} reads the developer's real settings, where
 * auto-connect is on by default, and auto-connect attaches to every agent on the
 * machine and resumes scripts on it. A developer checking a GUI change beside
 * someone's own game would drive that game. So the {@code run} task passes
 * {@code -D}{@value #PROPERTY}{@code =false}, which keeps auto-connect off for
 * that process only. Nothing is written to the settings file, and an installed
 * host, which never passes the property, behaves exactly as before.</p>
 *
 * @param isForcedOff auto-connect stays off for this process whatever the setting says
 */
public record AutoConnectOverride(boolean isForcedOff) {

    /** {@code false} keeps auto-connect off for the process. Any other value, or none, changes nothing. */
    public static final String PROPERTY = "botwithus.autoConnect";

    private static final String OFF = "false";

    /** No override: the saved setting decides. */
    public static AutoConnectOverride none() {
        return new AutoConnectOverride(false);
    }

    /** The override this JVM was started with. */
    public static AutoConnectOverride fromSystemProperties() {
        return of(System.getProperty(PROPERTY));
    }

    /** The override a {@value #PROPERTY} value asks for; {@code null} means unset. */
    public static AutoConnectOverride of(String value) {
        return new AutoConnectOverride(value != null && OFF.equalsIgnoreCase(value.strip()));
    }

    /** Whether auto-connect runs, given the saved setting. */
    public boolean allows(boolean isSettingOn) {
        return isSettingOn && !isForcedOff;
    }
}
