package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.management.Target;

import java.util.List;
import java.util.Objects;

/**
 * What the add-target row can offer, for any script: every group, and every
 * script on a client the host knows by account. The row leaves out what the
 * selected script already has.
 *
 * @param groups        each group, labelled "Woodcutters · 4 clients"
 * @param clientScripts each client script, labelled "Oakheart · Woodcutting"
 */
public record TargetChoices(List<Option> groups, List<Option> clientScripts) {

    /** Nothing to offer but the whole host. */
    public static final TargetChoices NONE = new TargetChoices(List.of(), List.of());

    /** One target the row can add, and its label in the list. */
    public record Option(Target target, String label) {
        public Option {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(label, "label");
        }
    }

    public TargetChoices {
        groups = List.copyOf(groups);
        clientScripts = List.copyOf(clientScripts);
    }
}
