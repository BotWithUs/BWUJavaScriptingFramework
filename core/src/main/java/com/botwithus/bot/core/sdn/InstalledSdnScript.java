package com.botwithus.bot.core.sdn;

import java.time.Instant;
import java.util.Objects;

/**
 * What the host recorded when it installed a script from the Store.
 *
 * @param catalogueId    the catalogue entry the script was installed for
 * @param installedBuild the entry's {@link SdnCatalogueEntry#currentBuild()} at the
 *                       time of the install; {@code null} when the catalogue did
 *                       not report one
 * @param installedAt    when the delivery was loaded
 */
public record InstalledSdnScript(String catalogueId, Integer installedBuild, Instant installedAt) {

    public InstalledSdnScript {
        Objects.requireNonNull(catalogueId, "catalogueId");
        Objects.requireNonNull(installedAt, "installedAt");
    }

    /** Whether {@code entry} offers a newer build than the one installed. */
    public SdnUpdateStatus updateAgainst(SdnCatalogueEntry entry) {
        return SdnUpdateStatus.of(installedBuild, entry.currentBuild());
    }
}
