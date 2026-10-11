package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.StoreCopies;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides whether a script is the one a catalogue entry names. This is how the
 * picker tells an installed subscription from one it must install, and which of
 * a delivered jar's scripts to start.
 *
 * <p>Two questions, two rules:</p>
 * <ul>
 *   <li><b>Is a local script this subscription?</b> Only when the catalogue's
 *       {@code scriptClass} names its class exactly. A blank {@code scriptClass}
 *       matches nothing: a name is not an identity, and a same-named local JAR by
 *       someone else would otherwise be hidden and started in the subscription's
 *       place. A <em>Store</em> copy is recognised by {@link StoreCopies}, which
 *       also reads the install ledger.</li>
 *   <li><b>Which script in a delivery is the one we asked for?</b> Whatever came
 *       out of the jar the launcher delivered for that entry; the installer pairs
 *       them. A jar normally provides one script. When it provides several, the
 *       one the catalogue names, by class and then by name, is chosen, and if that
 *       still leaves more than one, none is.</li>
 * </ul>
 */
public final class SubscriptionMatch {

    private SubscriptionMatch() {}

    /** Whether a local script of class {@code className} is this entry's script. */
    public static boolean isLoadedCopy(String className, SdnCatalogueEntry entry) {
        return StoreCopies.namesClass(entry, className);
    }

    /**
     * The script to start out of what {@code entry}'s jar provided.
     *
     * @param delivered    the scripts paired with {@code entry}
     * @param manifestName the name the runtime would register a script under
     * @return the script, or empty when the jar provided none or the choice is ambiguous
     */
    public static Optional<BotScript> scriptToStart(SdnCatalogueEntry entry, List<BotScript> delivered,
                                                    Function<BotScript, String> manifestName) {
        if (delivered.size() == 1) {
            return Optional.of(delivered.getFirst());
        }
        List<BotScript> byClass = delivered.stream()
                .filter(s -> StoreCopies.namesClass(entry, s.getClass().getName()))
                .toList();
        if (byClass.size() == 1) {
            return Optional.of(byClass.getFirst());
        }
        List<BotScript> byName = delivered.stream()
                .filter(s -> !entry.name().isBlank() && entry.name().equalsIgnoreCase(manifestName.apply(s)))
                .toList();
        return byName.size() == 1 ? Optional.of(byName.getFirst()) : Optional.empty();
    }
}
