package com.botwithus.bot.cli.gui.pages.installed;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the Installed scripts page shows in one frame. Immutable; the
 * model builds a new one when the host changes.
 *
 * @param scripts  every installed script, in display order, unfiltered
 * @param problems the "Failed to load" entries
 * @param clients  every client the Start-on dialog can offer
 */
public record InstalledView(InstalledHeader header, List<InstalledScript> scripts, List<LoadProblem> problems,
                            List<ClientChoice> clients) {

    private static final String SEP = " · ";

    public InstalledView {
        Objects.requireNonNull(header, "header");
        scripts = List.copyOf(scripts);
        problems = List.copyOf(problems);
        clients = List.copyOf(clients);
    }

    /** Runners looping normally, across every script and client. */
    public int runningCount() {
        return scripts.stream().mapToInt(s -> s.count(RunnerState.RUNNING)).sum();
    }

    /**
     * The line beside the title: {@code "scripts/ · 7 scripts · 5 running · reloaded 14:03:52"}.
     * The reload time appears once this page has reloaded.
     */
    public String meta() {
        StringBuilder out = new StringBuilder(header.folderLabel())
                .append(SEP).append(plural(scripts.size(), "script"))
                .append(SEP).append(runningCount()).append(" running");
        header.reloadedAt().ifPresent(t -> out.append(SEP).append("reloaded ").append(t));
        return out.toString();
    }

    /** What the sidebar's warning count counts: load problems plus scripts that stalled or crashed. */
    public int attentionCount() {
        return problems.size() + (int) scripts.stream().filter(InstalledScript::hasProblem).count();
    }

    public Optional<InstalledScript> find(String key) {
        return scripts.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    /** The folder holds nothing, not even a JAR that failed: the page shows its empty state. */
    public boolean isEmpty() {
        return scripts.isEmpty() && problems.isEmpty();
    }

    static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
