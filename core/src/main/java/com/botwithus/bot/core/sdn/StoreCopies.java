package com.botwithus.bot.core.sdn;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides whether a loaded script is the Store's copy of a catalogue entry.
 *
 * <p>This is the one rule both install screens use, the Store page and the
 * picker's "Start a script" list, so they cannot disagree about what is
 * installed. A loaded script of some class is an entry's copy when:
 *
 * <ul>
 *   <li>the install ledger recorded that class as delivered for that entry's id,
 *       which is how a v2 build is recognised although the catalogue names the
 *       agent-v1 class, and how an entry with no class at all is recognised; or
 *   <li>the catalogue names exactly that class, which covers a copy loaded before
 *       the ledger existed.
 * </ul>
 *
 * <p>A name never matches. A same-named local script by someone else is not the
 * subscription, and must not be hidden or started in its place.
 */
public final class StoreCopies {

    private final Function<String, Map<String, InstalledSdnScript>> installsOf;

    /**
     * @param installsOf the ledger's records for a catalogue id, keyed by delivered
     *                   class; see {@link InstalledScriptsLedger#installsOf}
     */
    public StoreCopies(Function<String, Map<String, InstalledSdnScript>> installsOf) {
        this.installsOf = installsOf;
    }

    /** The rule over {@code ledger}. */
    public static StoreCopies of(InstalledScriptsLedger ledger) {
        return new StoreCopies(ledger::installsOf);
    }

    /** Whether a loaded script of class {@code className} is the Store's copy of {@code entry}. */
    public boolean isCopyOf(SdnCatalogueEntry entry, String className) {
        return installsOf.apply(entry.id()).containsKey(className) || namesClass(entry, className);
    }

    /**
     * What the ledger recorded when {@code entry} was installed as {@code className},
     * which says whether a newer build is out. Empty for a copy with no record.
     */
    public Optional<InstalledSdnScript> recordOf(SdnCatalogueEntry entry, String className) {
        return Optional.ofNullable(installsOf.apply(entry.id()).get(className));
    }

    /** Whether this host has ever installed {@code entry} from the Store, loaded now or not. */
    public boolean wasInstalled(SdnCatalogueEntry entry) {
        return !installsOf.apply(entry.id()).isEmpty();
    }

    /** Whether the catalogue names exactly {@code className}; a blank class names nothing. */
    public static boolean namesClass(SdnCatalogueEntry entry, String className) {
        String named = entry.scriptClass();
        return named != null && !named.isBlank() && named.equals(className);
    }
}
