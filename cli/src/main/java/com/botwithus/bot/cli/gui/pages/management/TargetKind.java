package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.management.Target;

import java.util.List;
import java.util.function.Predicate;

/** The first choice of the add-target row: what kind of target to add. */
enum TargetKind {

    CLIENT_SCRIPT("Client script"),
    GROUP("Group"),
    WHOLE_HOST("Whole host");

    /** The whole host's one entry in the second choice. */
    static final String EVERY_CLIENT = "Every connected client";
    /** The second choice when there is nothing left of the kind to add. */
    static final String NOTHING_LEFT = "Already added";

    private final String label;

    TargetKind(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    static List<String> labels() {
        return List.of(CLIENT_SCRIPT.label, GROUP.label, WHOLE_HOST.label);
    }

    /**
     * What the second choice offers for this kind, leaving out what
     * {@code row} already has. The whole host is offered until it is added.
     */
    List<TargetChoices.Option> options(ManagementRow row, TargetChoices choices) {
        Predicate<TargetChoices.Option> isNew = option -> row.targets().stream()
                .noneMatch(existing -> existing.target().equals(option.target()));
        return switch (this) {
            case CLIENT_SCRIPT -> choices.clientScripts().stream().filter(isNew).toList();
            case GROUP -> choices.groups().stream().filter(isNew).toList();
            case WHOLE_HOST -> row.isWholeHost() ? List.of()
                    : List.of(new TargetChoices.Option(Target.host(), EVERY_CLIENT));
        };
    }
}
