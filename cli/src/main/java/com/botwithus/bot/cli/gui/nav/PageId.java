package com.botwithus.bot.cli.gui.nav;

import com.botwithus.bot.cli.gui.Icons;

/**
 * Every page the Advanced sidebar can show, in the order it lists them. The
 * section, label and icon are fixed here rather than on each {@link Page}, so a
 * page that is replaced keeps its place in the navigation.
 */
public enum PageId {

    DASHBOARD(NavSection.CLIENTS, "Dashboard", Icons.GAUGE_HIGH, ""),
    CLIENTS(NavSection.CLIENTS, "Clients", Icons.TH_LARGE, ""),
    CONNECTIONS(NavSection.CLIENTS, "Connections", Icons.PLUG, ""),
    GROUPS(NavSection.CLIENTS, "Groups", Icons.LAYER_GROUP, ""),
    INSTALLED(NavSection.ON_THIS_PC, "Installed scripts", Icons.FOLDER_OPEN,
            "Script JARs in your scripts folder. Start, stop and reload them here."),
    MANAGEMENT(NavSection.ON_THIS_PC, "Management", Icons.ROBOT,
            "Host-level scripts that run once for the whole host, not per client."),
    STORE(NavSection.ONLINE, "Script Store", Icons.BAG_SHOPPING,
            "Scripts you own or subscribe to on BotWithUs."),
    SETTINGS(NavSection.FOOTER, "Settings", Icons.GEAR, "");

    private final NavSection section;
    private final String label;
    private final String icon;
    private final String hint;

    PageId(NavSection section, String label, String icon, String hint) {
        this.section = section;
        this.label = label;
        this.icon = icon;
        this.hint = hint;
    }

    public NavSection section() {
        return section;
    }

    /** The sidebar label. */
    public String label() {
        return label;
    }

    /** The Font Awesome glyph drawn before the label. */
    public String icon() {
        return icon;
    }

    /** One sentence shown when the item is hovered, or empty for none. */
    public String hint() {
        return hint;
    }
}
