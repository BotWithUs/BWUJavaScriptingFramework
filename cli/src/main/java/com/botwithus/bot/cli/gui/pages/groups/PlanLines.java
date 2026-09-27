package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import java.util.ArrayList;
import java.util.List;

/** The "What happens" list the Start script dialog shows for a {@link StartPlan}, one line per kind of member. */
final class PlanLines {

    /** What the busy members' switch says it will do, under the switch. */
    static final String ALSO_START_NOTE = "It starts alongside: a client can run several scripts at once,"
            + " and what they run keeps running.";
    static final String SWITCH_NOTE = "What they run is stopped first, then it starts.";

    /**
     * One line of the list.
     *
     * @param lead drawn in the foreground colour; {@code rest} follows it in grey
     * @param kind which line it is, so the dialog knows where to put the busy switch
     */
    record Line(Kind kind, String icon, int iconColor, String lead, String rest) {

        /** The whole line as one string. */
        String text() {
            return lead + rest;
        }
    }

    enum Kind { STARTS, BUSY, ALREADY, QUEUED, UNRESOLVED }

    private PlanLines() {
    }

    static List<Line> of(StartPlan plan) {
        List<Line> lines = new ArrayList<>();
        List<StartPlan.Member> starts = plan.startsNow();
        lines.add(new Line(Kind.STARTS, Icons.PLAY, ImGuiTheme.COL_ACCENT, "Starts on " + starts.size(),
                starts.isEmpty() ? " now" : ": " + names(starts)));
        if (!plan.busy().isEmpty()) {
            int n = plan.busy().size();
            lines.add(new Line(Kind.BUSY, GroupWidgets.RIGHT_LEFT, ImGuiTheme.COL_FG2,
                    n + (n == 1 ? " is" : " are") + " running something else",
                    " (" + busyNames(plan.busy()) + ")."));
        }
        if (!plan.alreadyRunning().isEmpty()) {
            lines.add(new Line(Kind.ALREADY, Icons.CHECK, ImGuiTheme.COL_FG2,
                    "Already running on " + plan.alreadyRunning().size(),
                    ": " + names(plan.alreadyRunning()) + ". Nothing changes there."));
        }
        if (!plan.queued().isEmpty()) {
            lines.add(new Line(Kind.QUEUED, Icons.CLOCK, ImGuiTheme.COL_FG2,
                    plan.queued().size() + " not connected",
                    " (" + names(plan.queued()) + ")" + (plan.queued().size() == 1
                            ? " starts it when it comes back." : " start it when they come back.")));
        }
        if (plan.unresolved() > 0) {
            lines.add(new Line(Kind.UNRESOLVED, Icons.INFO, ImGuiTheme.COL_FG3,
                    GroupText.count(plan.unresolved(), "unknown client"),
                    " left out: not identified by account yet."));
        }
        return List.copyOf(lines);
    }

    /** What the busy members' switch says, for the choice made. */
    static String note(BusyChoice choice) {
        return switch (choice) {
            case ALSO_START -> ALSO_START_NOTE;
            case SWITCH -> SWITCH_NOTE;
        };
    }

    private static String names(List<StartPlan.Member> members) {
        return GroupText.join(members.stream().map(StartPlan.Member::account).toList());
    }

    private static String busyNames(List<StartPlan.Member> members) {
        return GroupText.join(members.stream()
                .map(member -> member.account() + ": " + GroupText.join(member.others()))
                .toList());
    }
}
