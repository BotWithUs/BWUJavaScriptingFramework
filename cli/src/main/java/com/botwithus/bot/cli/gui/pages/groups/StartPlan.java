package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What starting one script on a group will do, member by member: the "What
 * happens" list the Start script dialog shows before anything starts, and the
 * list the start then follows.
 *
 * @param script         the script to start
 * @param startNow       connected members running nothing else: it starts on them now
 * @param busy           connected members running other scripts: it starts on
 *                       them too, alongside or instead, as {@code choice} says
 * @param alreadyRunning connected members already running it: nothing changes
 * @param queued         members not connected: it starts when each is back
 * @param unresolved     members whose account is not known, which nothing can start on
 */
public record StartPlan(String script, BusyChoice choice, List<Member> startNow, List<Member> busy,
                        List<Member> alreadyRunning, List<Member> queued, int unresolved) {

    /**
     * One member in the plan.
     *
     * @param account the name the client shows, else its account UUID
     * @param others  the other scripts running on it; empty unless it is busy
     */
    public record Member(String uuid, String account, List<String> others) {
        public Member {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(account, "account");
            others = List.copyOf(others);
        }
    }

    public StartPlan {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(choice, "choice");
        startNow = List.copyOf(startNow);
        busy = List.copyOf(busy);
        alreadyRunning = List.copyOf(alreadyRunning);
        queued = List.copyOf(queued);
    }

    /**
     * Sorts {@code group}'s members by what starting {@code script} would do on
     * each, from what the host knows about them in {@code snapshot}.
     */
    public static StartPlan of(ClientGroup group, GroupsSnapshot snapshot, String script, BusyChoice choice) {
        List<Member> now = new ArrayList<>();
        List<Member> busy = new ArrayList<>();
        List<Member> already = new ArrayList<>();
        List<Member> queued = new ArrayList<>();
        for (String uuid : group.members()) {
            MemberFacts facts = snapshot.facts(uuid);
            String account = GroupText.accountOf(facts);
            if (!facts.isConnected()) {
                queued.add(new Member(uuid, account, List.of()));
            } else if (facts.isRunning(script)) {
                already.add(new Member(uuid, account, List.of()));
            } else if (facts.active().isEmpty()) {
                now.add(new Member(uuid, account, List.of()));
            } else {
                busy.add(new Member(uuid, account, facts.active().stream().map(ScriptFact::name).toList()));
            }
        }
        return new StartPlan(script, choice, now, busy, already, queued, group.unresolved().size());
    }

    /** The members it starts on now: those running nothing else, and those that are. */
    public List<Member> startsNow() {
        List<Member> all = new ArrayList<>(startNow);
        all.addAll(busy);
        return List.copyOf(all);
    }

    /** Whether starting would do anything: start it somewhere now, or queue it somewhere. */
    public boolean canStart() {
        return !startNow.isEmpty() || !busy.isEmpty() || !queued.isEmpty();
    }

    /** Whether starting stops anything: only a switch on busy members does. */
    public boolean stopsOthers() {
        return choice == BusyChoice.SWITCH && !busy.isEmpty();
    }
}
