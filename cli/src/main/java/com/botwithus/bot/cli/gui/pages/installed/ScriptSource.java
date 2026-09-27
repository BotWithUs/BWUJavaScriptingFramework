package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.gui.Icons;

/**
 * Where an installed script came from, so the local / online split the sidebar
 * draws carries through to each script.
 */
public enum ScriptSource {

    /** Installed from the Script Store; recorded by the host when it installed it. */
    STORE("Store", "Script Store", Icons.BAG_SHOPPING, "Installed from the Script Store"),

    /** A JAR dropped into the scripts folder or built on this PC. */
    LOCAL("Local build", "local build", Icons.HAMMER, "A JAR you dropped into the scripts folder or built yourself");

    private final String label;
    private final String lowerLabel;
    private final String icon;
    private final String hint;

    ScriptSource(String label, String lowerLabel, String icon, String hint) {
        this.label = label;
        this.lowerLabel = lowerLabel;
        this.icon = icon;
        this.hint = hint;
    }

    /** The badge text. */
    public String label() {
        return label;
    }

    /** The same, mid-sentence: "Woodcutting · local build". */
    public String lowerLabel() {
        return lowerLabel;
    }

    public String icon() {
        return icon;
    }

    /** The badge's tooltip. */
    public String hint() {
        return hint;
    }
}
