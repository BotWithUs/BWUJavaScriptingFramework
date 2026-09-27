package com.botwithus.bot.cli.scripts;

import java.util.List;

/**
 * What a reload of management scripts did.
 *
 * @param after     what it started afterwards
 * @param loaded    how many management scripts it registered
 * @param restarted the scripts started again under {@link AfterReload#RESTART_RUNNING}
 * @param missing   the scripts that were running but are no longer in the folder
 */
public record ManagementReload(AfterReload after, int loaded, List<String> restarted, List<String> missing) {

    public ManagementReload {
        restarted = List.copyOf(restarted);
        missing = List.copyOf(missing);
    }
}
