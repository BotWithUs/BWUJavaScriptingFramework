package com.botwithus.bot.cli.gui.pages.installed;

import java.util.Objects;
import java.util.Optional;

/**
 * The page header's state.
 *
 * @param folderLabel          the scripts folder as the sidebar shows it, such as {@code "scripts/"}
 * @param reloadedAt           when this page last finished a reload, as {@code HH:mm:ss};
 *                             empty until it has done one
 * @param isReloading          a reload is running now
 * @param isWatching           the folder watch is actually running, not merely switched on
 * @param isRestartAfterReload a reload starts again what was running before it
 */
public record InstalledHeader(String folderLabel, Optional<String> reloadedAt, boolean isReloading,
                              boolean isWatching, boolean isRestartAfterReload) {

    public InstalledHeader {
        Objects.requireNonNull(folderLabel, "folderLabel");
        Objects.requireNonNull(reloadedAt, "reloadedAt");
    }
}
