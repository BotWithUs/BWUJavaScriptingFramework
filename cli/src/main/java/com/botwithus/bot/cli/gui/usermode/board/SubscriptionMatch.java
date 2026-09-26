package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.sdn.SdnCatalogueEntry;

/**
 * Decides whether a script is the one a catalogue entry names. This is how the
 * picker tells an installed subscription from one it must install, and which of
 * a delivered bundle's scripts to start.
 *
 * <p>Two questions, two rules:</p>
 * <ul>
 *   <li><b>Is a script that is already loaded this subscription?</b> Only when
 *       the catalogue's {@code scriptClass} names its class exactly. A blank
 *       {@code scriptClass} matches nothing already loaded: a name is not an
 *       identity, and a same-named local JAR by someone else would otherwise be
 *       hidden and started in the subscription's place.</li>
 *   <li><b>Which script in a delivery is the one we asked for?</b> The class
 *       when the catalogue gives one, else the manifest name. The delivery holds
 *       only what was requested, so the name is safe there; the caller still
 *       checks that the runner it starts holds the delivered instance.</li>
 * </ul>
 */
public final class SubscriptionMatch {

    private SubscriptionMatch() {}

    /** Whether an already-loaded script of class {@code className} is this entry's script. */
    public static boolean isLoadedCopy(String className, SdnCatalogueEntry entry) {
        return hasScriptClass(entry) && entry.scriptClass().equals(className);
    }

    /**
     * Whether a script just delivered for {@code entry} is its script.
     *
     * @param className    the delivered script's fully-qualified class name
     * @param manifestName the name the runtime registers it under
     */
    public static boolean isDeliveryOf(String className, String manifestName, SdnCatalogueEntry entry) {
        if (hasScriptClass(entry)) {
            return entry.scriptClass().equals(className);
        }
        return !entry.name().isBlank() && entry.name().equalsIgnoreCase(manifestName);
    }

    private static boolean hasScriptClass(SdnCatalogueEntry entry) {
        return entry.scriptClass() != null && !entry.scriptClass().isBlank();
    }
}
