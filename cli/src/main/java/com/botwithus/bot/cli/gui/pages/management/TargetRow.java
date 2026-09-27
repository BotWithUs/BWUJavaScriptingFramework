package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.management.Target;

import java.util.Objects;

/**
 * One of a management script's targets, as the page names it.
 *
 * @param label       "Whole host", a group's name, or "Oakheart · Woodcutting"
 * @param sub         the line under it: what the target covers
 * @param ownSettings how many settings the target sets itself, over what it inherits
 */
public record TargetRow(Target target, String label, String sub, int ownSettings) {

    public TargetRow {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(sub, "sub");
    }

    /** Whether this is the whole host, which uses only the defaults and so has no settings of its own. */
    public boolean isWholeHost() {
        return switch (target) {
            case Target.Host _ -> true;
            case Target.Group _, Target.ClientScript _ -> false;
        };
    }
}
