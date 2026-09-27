package com.botwithus.bot.cli.gui.pages.installed;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The words beside a row's dots, such as "2 of 4 running · 1 stalled", in
 * parts so each can take its own colour: the running count is emphasised,
 * stalled is amber, crashed and cut off are red.
 */
public record RunSummary(List<Part> parts) {

    /** How a part is drawn. */
    public enum Tone { STRONG, PLAIN, WARN, DANGER }

    /** One run of same-coloured text. */
    public record Part(String text, Tone tone) {}

    /** What a row with no client says. */
    public static final String NOWHERE = "Not on any client";
    private static final String SEPARATOR = " · ";

    public RunSummary {
        parts = List.copyOf(parts);
    }

    /** The summary of {@code runs}, one per client the script is on. */
    public static RunSummary of(List<ClientRun> runs) {
        if (runs.isEmpty()) {
            return new RunSummary(List.of(new Part(NOWHERE, Tone.PLAIN)));
        }
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(Long.toString(countOf(runs, RunnerState.RUNNING)), Tone.STRONG));
        parts.add(new Part(" of " + runs.size() + " running", Tone.PLAIN));
        addCount(parts, countOf(runs, RunnerState.STALLED), "stalled", Tone.WARN);
        addCount(parts, countOf(runs, RunnerState.CRASHED), "crashed", Tone.DANGER);
        addCount(parts, countOf(runs, RunnerState.CUT_OFF), "cut off", Tone.DANGER);
        return new RunSummary(parts);
    }

    private static long countOf(List<ClientRun> runs, RunnerState state) {
        return runs.stream().filter(r -> r.state() == state).count();
    }

    private static void addCount(List<Part> parts, long n, String what, Tone tone) {
        if (n > 0) {
            parts.add(new Part(SEPARATOR, Tone.PLAIN));
            parts.add(new Part(n + " " + what, tone));
        }
    }

    /** The whole line as plain text. */
    public String text() {
        return parts.stream().map(Part::text).collect(Collectors.joining());
    }
}
