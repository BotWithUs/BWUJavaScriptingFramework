package com.botwithus.bot.cli.gui.pages.management;

import java.util.List;

/**
 * The list's "Applies to" cell: the first {@value #SHOWN} targets as chips,
 * then "+N" for the rest, or "Not applied" when there are none.
 *
 * @param shown  the targets drawn as chips, in order
 * @param hidden the targets folded into "+N", for its tooltip
 */
public record AppliesTo(List<TargetRow> shown, List<TargetRow> hidden) {

    /** How many targets the cell names before it folds the rest into "+N". */
    public static final int SHOWN = 2;
    /** The cell of a script with no targets: it runs, but manages nothing. */
    public static final String NOT_APPLIED = "Not applied";

    public AppliesTo {
        shown = List.copyOf(shown);
        hidden = List.copyOf(hidden);
    }

    /** The cell for {@code targets}, in the order the script lists them. */
    public static AppliesTo of(List<TargetRow> targets) {
        int cut = Math.min(SHOWN, targets.size());
        return new AppliesTo(targets.subList(0, cut), targets.subList(cut, targets.size()));
    }

    public boolean isNotApplied() {
        return shown.isEmpty();
    }

    /** "+2" for two folded targets; empty when none are. */
    public String more() {
        return hidden.isEmpty() ? "" : "+" + hidden.size();
    }

    /** The folded targets' labels, for the "+N" chip's tooltip. */
    public String hiddenLabels() {
        return String.join(", ", hidden.stream().map(TargetRow::label).toList());
    }
}
