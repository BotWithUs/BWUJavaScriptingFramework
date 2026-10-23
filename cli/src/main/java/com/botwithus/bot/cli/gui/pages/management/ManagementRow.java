package com.botwithus.bot.cli.gui.pages.management;

import java.util.List;
import java.util.Objects;

/**
 * One management script as the page shows it.
 *
 * @param targets  what it manages: the whole host first, then groups, then client scripts
 * @param activity its orchestrator calls, newest first
 */
public record ManagementRow(ScriptAbout about, List<TargetRow> targets, RunHealth health,
                            List<ActivityRow> activity) {

    public ManagementRow {
        Objects.requireNonNull(about, "about");
        Objects.requireNonNull(health, "health");
        targets = List.copyOf(targets);
        activity = List.copyOf(activity);
    }

    public String name() {
        return about.name();
    }

    /** The list's Applies to cell. */
    public AppliesTo appliesTo() {
        return AppliesTo.of(targets);
    }

    /** It has no targets: it may run, but manages nothing. */
    public boolean isNotApplied() {
        return targets.isEmpty();
    }

    /** It manages every client, so it has only the defaults and nothing else to add. */
    public boolean isWholeHost() {
        return targets.stream().anyMatch(TargetRow::isWholeHost);
    }

    /** The detail header's line: "Applies to Woodcutters", "Applies to 4 targets" or "Not applied yet". */
    public String appliesLine() {
        return switch (targets.size()) {
            case 0 -> "Not applied yet";
            case 1 -> "Applies to " + targets.getFirst().label();
            default -> "Applies to " + targets.size() + " targets";
        };
    }
}
