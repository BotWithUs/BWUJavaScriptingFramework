package com.botwithus.bot.cli.scripts;

import java.util.List;

/**
 * What a reload of client scripts did.
 *
 * @param after     what it started afterwards
 * @param clients   each reloaded client with the number of scripts it loaded, in reload order
 * @param restarted the pairs started again under {@link AfterReload#RESTART_RUNNING}
 * @param missing   the pairs that were running but could not be started again,
 *                  because the reload no longer yields a script by that name
 */
public record ReloadSummary(AfterReload after, List<ClientReload> clients,
                            List<RunningPair> restarted, List<RunningPair> missing) {

    public ReloadSummary {
        clients = List.copyOf(clients);
        restarted = List.copyOf(restarted);
        missing = List.copyOf(missing);
    }

    /**
     * One reloaded client.
     *
     * @param connection the client's connection name
     * @param loaded     how many scripts the reload registered on it
     */
    public record ClientReload(String connection, int loaded) {
    }

    /** One line per pair that could not be restarted, for a console. */
    public List<String> missingLines() {
        return missing.stream()
                .map(p -> "[" + p.connection() + "] Not restarted: " + p.script()
                        + " is no longer in the scripts folder.")
                .toList();
    }
}
