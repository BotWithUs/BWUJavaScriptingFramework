package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Icons;

/**
 * The Settings page's sections, in the order the page and its section list show
 * them.
 */
public enum SettingsSection {

    CONNECTING("Connecting to game clients", "Connecting", Icons.PLUG,
            "How the host finds and holds on to game clients."),
    RECONNECTING("Reconnecting", "Reconnecting", Icons.ROTATE,
            "What happens when a client stops answering. The host stays open either way."),
    ACCOUNTS("Accounts and auto-start", "Accounts", Icons.USER,
            "Clients are remembered by account UUID. When an account comes back, "
                    + "the scripts listed here start again."),
    SCRIPTS("Scripts", "Scripts", Icons.FOLDER_OPEN,
            "Loading and reloading script JARs from the scripts folder."),
    NOTIFICATIONS("Notifications", "Notifications", Icons.BELL,
            "Pop-ups in the top-right corner. Everything also goes to Logs."),
    INTEGRATIONS("Integrations", "Integrations", IntegrationIcons.SHARE_NODES,
            "Send the same alerts to your phone or a chat channel, so you know when a client stops "
                    + "while you’re away."),
    INTERFACE("Interface", "Interface", Icons.DISPLAY, ""),
    WALKING("Walking", "Walking", Icons.MAP,
            "How the world walker plans routes. Applies to walks started after a change."),
    DIAGNOSTICS("Diagnostics", "Diagnostics", Icons.GAUGE_HIGH,
            "Numbers behind the Dashboard, and debug drawing over the game. "
                    + "Collecting the numbers costs very little."),
    ALL_KEYS("All config keys", "All config keys", Icons.CODE,
            "Everything in config.properties, for keys that don't have a control above. "
                    + "Same as config show / config set in the console."),
    ABOUT("About", "About", Icons.INFO, "");

    private final String title;
    private final String shortTitle;
    private final String icon;
    private final String description;

    SettingsSection(String title, String shortTitle, String icon, String description) {
        this.title = title;
        this.shortTitle = shortTitle;
        this.icon = icon;
        this.description = description;
    }

    /** The heading over the section. */
    public String title() {
        return title;
    }

    /** The label in the section list on the left. */
    public String shortTitle() {
        return shortTitle;
    }

    public String icon() {
        return icon;
    }

    /** One line under the heading, or empty for none. */
    public String description() {
        return description;
    }
}
