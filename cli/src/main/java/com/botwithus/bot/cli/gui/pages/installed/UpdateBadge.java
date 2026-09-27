package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.core.sdn.InstalledSdnScript;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnUpdateStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * The Store has a newer build of a script than the one installed here: the
 * row's small "v2.1 in Store" pill and the About tab's update panel.
 *
 * <p>Only the site's build numbers are compared. The catalogue's version label
 * is free text, so it names the update but never decides whether there is one.</p>
 *
 * @param badge    the pill after the version, such as {@code "v2.1 in Store"}
 * @param headline the update panel's first line, such as {@code "v2.1 is in the Store"}
 * @param detail   the panel's second line, which says what is installed now
 */
public record UpdateBadge(String badge, String headline, String detail) {

    private static final String VERSION_PREFIX = "v";

    public UpdateBadge {
        Objects.requireNonNull(badge, "badge");
        Objects.requireNonNull(headline, "headline");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * The badge for a script the host installed from the Store, or empty when
     * there is no newer build, or when either build is unknown.
     *
     * @param installed        what the host recorded at install; empty for a local build
     * @param entry            the script's catalogue entry; empty when the catalogue does not list it
     * @param installedVersion the version label of the copy installed here
     */
    public static Optional<UpdateBadge> of(Optional<InstalledSdnScript> installed,
                                           Optional<SdnCatalogueEntry> entry, String installedVersion) {
        if (installed.isEmpty() || entry.isEmpty()) {
            return Optional.empty();
        }
        return switch (installed.get().updateAgainst(entry.get())) {
            case SdnUpdateStatus.Available a -> Optional.of(badgeFor(a, entry.get().version(), installedVersion));
            case SdnUpdateStatus.UpToDate ignored -> Optional.empty();
            case SdnUpdateStatus.Unknown ignored -> Optional.empty();
        };
    }

    /**
     * Names the update by the Store's version label when it names a different
     * version than the one installed; a label that did not move would read as
     * no change at all, so the build number stands in.
     */
    private static UpdateBadge badgeFor(SdnUpdateStatus.Available update, String storeVersion,
                                        String installedVersion) {
        String store = bare(storeVersion);
        boolean isLabelled = !store.isEmpty() && !store.equalsIgnoreCase(bare(installedVersion));
        String name = isLabelled ? VERSION_PREFIX + store : "build " + update.currentBuild();
        String headline = isLabelled ? name : "Build " + update.currentBuild();
        String build = "build " + update.installedBuild();
        String have = bare(installedVersion).isEmpty() ? build
                : VERSION_PREFIX + bare(installedVersion) + " (" + build + ")";
        return new UpdateBadge(name + " in Store", headline + " is in the Store",
                "You have " + have + ". Update it from the Script Store.");
    }

    /** {@code "v2.1"} and {@code "2.1"} alike as {@code "2.1"}; {@code null} as empty. */
    private static String bare(String version) {
        if (version == null) {
            return "";
        }
        String v = version.strip();
        return v.regionMatches(true, 0, VERSION_PREFIX, 0, VERSION_PREFIX.length())
                ? v.substring(VERSION_PREFIX.length()).strip() : v;
    }
}
