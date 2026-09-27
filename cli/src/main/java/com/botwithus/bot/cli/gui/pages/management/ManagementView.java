package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.gui.pages.installed.LoadProblem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the Management page shows in one frame. Immutable; the model
 * builds a new one when the host changes.
 *
 * @param folderLabel the management folder as the page names it, such as {@code "scripts/management/"}
 * @param scripts     every loaded management script, by name
 * @param problems    the "Failed to load" entries
 * @param choices     what the add-target row can offer
 */
public record ManagementView(String folderLabel, boolean isReloading, List<ManagementRow> scripts,
                             List<LoadProblem> problems, TargetChoices choices) {

    private static final String SEP = " · ";

    public ManagementView {
        Objects.requireNonNull(folderLabel, "folderLabel");
        Objects.requireNonNull(choices, "choices");
        scripts = List.copyOf(scripts);
        problems = List.copyOf(problems);
    }

    public int runningCount() {
        return (int) scripts.stream().filter(s -> s.health().state().isRunning()).count();
    }

    /** The line beside the title: {@code "scripts/management/ · 3 loaded · 2 running"}. */
    public String meta() {
        return folderLabel + SEP + scripts.size() + " loaded" + SEP + runningCount() + " running";
    }

    /** What the sidebar's warning count counts: JARs that failed to load, and scripts whose last run crashed. */
    public int attentionCount() {
        return problems.size() + (int) scripts.stream().filter(s -> s.health().state().isCrashed()).count();
    }

    public Optional<ManagementRow> find(String name) {
        return scripts.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /** The folder holds nothing, not even a JAR that failed: the page shows its empty state. */
    public boolean isEmpty() {
        return scripts.isEmpty() && problems.isEmpty();
    }
}
