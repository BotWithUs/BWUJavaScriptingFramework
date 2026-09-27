package com.botwithus.bot.cli.gui.pages.connections;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the detail pane shows for the selected row, beyond the row itself.
 * Immutable.
 *
 * @param row                the row it is about
 * @param resumeAfterRestart whether the account's scripts start again when it
 *                           comes back; empty when the client has no account to
 *                           remember it by
 * @param history            the client's history, newest first
 * @param scripts            the scripts running, crashed or set to start on it
 * @param groups             the groups it is in, by name
 * @param policy             the reconnect back-off, in words
 */
public record ConnectionDetail(ConnectionRow row, Optional<Boolean> resumeAfterRestart,
                               List<TimelineEntry> history, List<ScriptChip> scripts, List<String> groups,
                               String policy) {

    public ConnectionDetail {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(resumeAfterRestart, "resumeAfterRestart");
        history = List.copyOf(history);
        scripts = List.copyOf(scripts);
        groups = List.copyOf(groups);
        Objects.requireNonNull(policy, "policy");
    }
}
