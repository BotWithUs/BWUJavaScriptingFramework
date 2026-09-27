package com.botwithus.bot.core.sdn;

/**
 * Whether the Store has a newer build of a script than the one installed here.
 *
 * <p>Only the site's build number is compared. A script's version label is free
 * text and says nothing about order.
 */
public sealed interface SdnUpdateStatus {

    /**
     * Either side's build is unknown: the script was installed before builds were
     * recorded, or the catalogue did not report one. Show no badge either way.
     */
    record Unknown() implements SdnUpdateStatus {
    }

    /**
     * The installed build is the Store's build, or newer than it: the site can make
     * an older build current again, and that is not offered as an update.
     */
    record UpToDate(int installedBuild) implements SdnUpdateStatus {
    }

    /** The Store has build {@code currentBuild}; this host installed {@code installedBuild}. */
    record Available(int installedBuild, int currentBuild) implements SdnUpdateStatus {
    }

    /**
     * Compares an installed build with the catalogue's current one.
     *
     * @param installedBuild the build recorded at install; {@code null} when unknown
     * @param currentBuild   {@link SdnCatalogueEntry#currentBuild()}; {@code null} when unknown
     */
    public static SdnUpdateStatus of(Integer installedBuild, Integer currentBuild) {
        if (installedBuild == null || currentBuild == null) {
            return new Unknown();
        }
        if (installedBuild < currentBuild) {
            return new Available(installedBuild, currentBuild);
        }
        return new UpToDate(installedBuild);
    }
}
