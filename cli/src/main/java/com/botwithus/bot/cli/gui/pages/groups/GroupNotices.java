package com.botwithus.bot.cli.gui.pages.groups;

import java.util.List;

/** The sentences the Groups page reports its actions with. */
final class GroupNotices {

    private GroupNotices() {
    }

    /** What starting a script on a group did, following {@code plan}. */
    static String started(StartPlan plan) {
        int now = plan.startsNow().size();
        StringBuilder text = new StringBuilder("Starting ").append(plan.script()).append(" on ")
                .append(GroupText.count(now, "client")).append('.');
        if (plan.stopsOthers()) {
            text.append(" Stopped what was running on ").append(GroupText.count(plan.busy().size(), "client"))
                    .append(" first.");
        }
        int queued = plan.queued().size();
        if (queued > 0) {
            text.append(' ').append(GroupText.count(queued, "client")).append(queued == 1 ? " starts" : " start")
                    .append(" it when back.");
        }
        return text.toString();
    }

    /**
     * What adding clients to {@code group} did.
     *
     * @param added   the names of the clients now members
     * @param refused "name: reason" for each client that could not join
     */
    static Notice added(String group, List<String> added, List<String> refused) {
        StringBuilder text = new StringBuilder();
        if (!added.isEmpty()) {
            text.append("Added ").append(GroupText.count(added.size(), "client")).append(" to ").append(group)
                    .append('.');
        }
        for (String refusal : refused) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append("Could not add ").append(refusal);
        }
        return refused.isEmpty() ? Notice.info(text.toString()) : Notice.problem(text.toString());
    }
}
