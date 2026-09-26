package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

/**
 * Decides whether a loaded script is the same script as a catalogue entry, which
 * is how the picker tells an installed subscription from one it must install,
 * and which of a delivered bundle's scripts to start.
 *
 * <p>The catalogue's {@code scriptClass} is the identity when the site sends it,
 * compared exactly. Only when it is blank does the manifest name stand in,
 * case-insensitively, because a name is all there is left to go on.</p>
 */
public final class SubscriptionMatch {

    private SubscriptionMatch() {}

    /**
     * @param className    the loaded script's fully-qualified class name
     * @param manifestName the name the runtime registers it under
     */
    public static boolean isSameScript(String className, String manifestName, SdnCatalogueEntry entry) {
        String scriptClass = entry.scriptClass();
        if (scriptClass != null && !scriptClass.isBlank()) {
            return scriptClass.equals(className);
        }
        return !entry.name().isBlank() && entry.name().equalsIgnoreCase(manifestName);
    }
}
